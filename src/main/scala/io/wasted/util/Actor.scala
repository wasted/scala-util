package io.wasted.util

import scala.concurrent.duration.Duration
import java.util.concurrent.{ ConcurrentLinkedQueue, Executor, Executors }
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wasted lightweight Actor implementation based on Viktor Klang's mini-Actor.
 *
 * @param ec ExecutionContext to be used
 */
abstract class Wactor(implicit ec: Executor = Wactor.executionContext) extends Wactor.Address with Runnable with Logger {
  import Wactor._
  override protected def loggerName: String
  protected def receive: PartialFunction[Any, Any]

  // Our little indicator if this actor is on or not
  val on = new AtomicInteger(0)

  // Our awesome little mailboxes, free of blocking and evil
  private final val mboxHigh = new ConcurrentLinkedQueue[Any]
  private final val mboxNormal = new ConcurrentLinkedQueue[Any]

  // Rebindable top of the mailbox, bootstrapped to Dispatch behavior
  private var behavior: Behavior = Dispatch(receive)

  // Add a message with normal priority
  final override def !(msg: Any): Unit = behavior match {
    case dead @ Die.`like` => dead(msg) // Efficiently bail out if we're _known_ to be dead
    case _ => mboxNormal.offer(msg); async() // Enqueue the message onto the mailbox and try to schedule for execution
  }

  // Add a message with high priority
  final override def !!(msg: Any): Unit = behavior match {
    case dead @ Die.`like` => dead(msg) // Efficiently bail out if we're _known_ to be dead
    case _ => mboxHigh.offer(msg); async() // Enqueue the message onto the mailbox and try to schedule for execution
  }

  final def run(): Unit = try {
    if (on.get == 1) behavior = behavior(if (mboxHigh.isEmpty) mboxNormal.poll else mboxHigh.poll)(behavior)
  } finally {
    // Switch ourselves off, and then see if we should be rescheduled for execution
    on.set(0)
    async()
  }

  // If there's something to process, and we're not already scheduled
  private final def async() {
    if (!(mboxHigh.isEmpty && mboxNormal.isEmpty) && on.compareAndSet(0, 1))
      scala.util.Try(ec.execute(this)) match {
        case scala.util.Success(f) =>
        case scala.util.Failure(e) =>
          on.set(0)
          throw e
      }
  }
}

/**
 * Wasted lightweight Actor companion
 */
object Wactor {
  private[util] val executionContext: Executor = Executors.newCachedThreadPool
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
    def apply(pf: PartialFunction[Any, Any]) = (msg: Any) => {
      pf(msg)
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
    def scheduleOnce(msg: Any, initialDelay: Duration): Schedule.Action =
      Schedule.once(() => { this ! msg }, initialDelay)

    /**
     * Schedule an event once on this Wactor for High Priority.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     */
    def scheduleOnceHigh(msg: Any, initialDelay: Duration): Schedule.Action =
      Schedule.once(() => { this !! msg }, initialDelay)

    /**
     * Schedule an event over and over again on this Wactor for High Priority.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     * @param delay Delay to be called after the first firing
     */
    def scheduleHigh(msg: Any, initialDelay: Duration, delay: Duration): Schedule.Action =
      Schedule.again(() => { this !! msg }, initialDelay, delay)

    /**
     * Schedule an event over and over again on this Wactor.
     *
     * @param msg Message to be sent to the actor
     * @param initialDelay Initial delay before first firing
     * @param delay Delay to be called after the first firing
     */
    def schedule(msg: Any, initialDelay: Duration, delay: Duration): Schedule.Action =
      Schedule.again(() => { this ! msg }, initialDelay, delay)
  }
}
