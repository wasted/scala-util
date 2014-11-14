package io.wasted.util

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ ConcurrentLinkedQueue, Executor, Executors, ForkJoinPool }

import scala.concurrent.duration.Duration
import scala.util.{ Failure, Success, Try }

/**
 * Wasted lightweight Actor implementation based on Viktor Klang's mini-Actor (https://gist.github.com/2362563).
 *
 * @param ec ExecutionContext to be used
 */
abstract class Wactor(maxQueueSize: Int = -1)(implicit ec: Executor = Wactor.ecForkJoin) extends Wactor.Address with Runnable with Logger {
  protected def receive: PartialFunction[Any, Any]

  /**
   * Netty-style exceptionCaught method which will get all exceptions caught while running a job.
   */
  protected def exceptionCaught(e: Throwable) {
    e.printStackTrace()
  }

  // Our little indicator if this actor is on or not
  val on = new AtomicInteger(0)

  // Queue/LRU management counter
  private val queueSize = new AtomicInteger(0)

  // Our awesome little mailboxes, free of blocking and evil
  private final val mboxHigh = new ConcurrentLinkedQueue[Any]
  private final val mboxNormal = new ConcurrentLinkedQueue[Any]
  import io.wasted.util.Wactor._

  // Rebindable top of the mailbox, bootstrapped to Dispatch behavior
  private var behavior: Behavior = Dispatch(this, receive)

  // Add a message with normal priority
  final override def !(msg: Any): Unit = behavior match {
    case dead @ Die.`like` => dead(msg) // Efficiently bail out if we're _known_ to be dead
    case _ =>
      var dontQueueBecauseWeAreNotHighEnough = false // ;)
      // if our queue is considered full, discard the head
      if (maxQueueSize > 0 && queueSize.incrementAndGet > maxQueueSize) {
        dontQueueBecauseWeAreNotHighEnough = mboxNormal.poll == null
        queueSize.decrementAndGet
      }
      // Enqueue the message onto the mailbox only if we are high enough
      if (!dontQueueBecauseWeAreNotHighEnough) mboxNormal.offer(msg)
      async() // try to schedule for execution
  }

  // Add a message with high priority
  final override def !!(msg: Any): Unit = behavior match {
    case dead @ Die.`like` => dead(msg) // Efficiently bail out if we're _known_ to be dead
    case _ =>
      // if our queue is considered full, discard the head of our **normal inbox**
      if (maxQueueSize > 0 && queueSize.incrementAndGet > maxQueueSize) {
        if (mboxNormal.poll == null) mboxHigh.poll
        queueSize.decrementAndGet
      }
      mboxHigh.offer(msg) // Enqueue the message onto the mailbox
      async() // try to schedule for execution
  }

  final def run(): Unit = try {
    if (on.get == 1) behavior = behavior({
      queueSize.decrementAndGet
      val ret = if (mboxHigh.isEmpty) mboxNormal.poll else mboxHigh.poll
      if (ret == Die) {
        mboxNormal.clear
        mboxHigh.clear
      }
      ret
    })(behavior)
  } finally {
    // Switch ourselves off, and then see if we should be rescheduled for execution
    on.set(0)
    async()
  }

  // If there's something to process, and we're not already scheduled
  private final def async() {
    if (!(mboxHigh.isEmpty && mboxNormal.isEmpty) && on.compareAndSet(0, 1))
      try { ec.execute(this) } catch { case e: Throwable => on.set(0); throw e }
  }
}

/**
 * Wasted lightweight Actor companion
 */
object Wactor {
  private[util] lazy val ecForkJoin: Executor = new ForkJoinPool
  private[util] lazy val ecThreadPool: Executor = Executors.newCachedThreadPool
  private[util]type Behavior = Any => Effect
  private[util] sealed trait Effect extends (Behavior => Behavior)

  /* Effect which tells the actor to keep the current behavior. */
  private[util] case object Stay extends Effect {
    def apply(old: Behavior): Behavior = old
  }

  /* Effect hwich tells the actor to use a new behavior. */
  case class Become(like: Behavior) extends Effect {
    def apply(old: Behavior): Behavior = like
  }

  /* Default dispatch Behavior. */
  object Dispatch {
    val fallbackPF: PartialFunction[Any, Any] = { case _ => }
    def apply(actor: Wactor, pf: PartialFunction[Any, Any]) = (msg: Any) => {
      val newpf: (Any => Any) = pf orElse fallbackPF
      Try(newpf(msg)) match {
        case Success(e) =>
        case Failure(e) => actor.exceptionCaught(e)
      }
      Stay
    }
  }

  /* Behavior to tell the actor he's dead. */
  final val Die = Become(msg => {
    println("Dropping msg [" + msg + "] due to severe case of death.")
    Stay
  })

  /* The notion of an Address to where you can post messages to. */
  trait Address {
    def !(msg: Any): Unit
    def !!(msg: Any): Unit = this ! (msg)

    /**
     * Schedule an event once on this Wactor.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     */
    def scheduleOnce(msg: Any, initialDelay: Duration)(implicit timer: WheelTimer): Schedule.Action =
      Schedule.once(() => { this ! msg }, initialDelay)

    /**
     * Schedule an event once on this Wactor for High Priority.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     */
    def scheduleOnceHigh(msg: Any, initialDelay: Duration)(implicit timer: WheelTimer): Schedule.Action =
      Schedule.once(() => { this !! msg }, initialDelay)

    /**
     * Schedule an event over and over again on this Wactor for High Priority.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     * @param delay Delay to be called after the first firing
     */
    def scheduleHigh(msg: Any, initialDelay: Duration, delay: Duration)(implicit timer: WheelTimer): Schedule.Action =
      Schedule.again(() => { this !! msg }, initialDelay, delay)

    /**
     * Schedule an event over and over again on this Wactor.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     * @param delay Delay to be called after the first firing
     */
    def schedule(msg: Any, initialDelay: Duration, delay: Duration)(implicit timer: WheelTimer): Schedule.Action =
      Schedule.again(() => { this ! msg }, initialDelay, delay)
  }
}
