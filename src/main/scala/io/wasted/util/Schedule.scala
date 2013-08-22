package io.wasted.util

import scala.concurrent.duration.Duration
import io.netty.util.{ Timeout, TimerTask }
import java.util.concurrent.{ TimeUnit, ConcurrentHashMap }

/**
 * Wasted Scheduler based on Netty's HashedWheelTimer
 */
object Schedule extends Logger {
  private[util] val timerMillis = Config.getInt("wheel.tickMillis", 100)
  private[util] val wheelSize = Config.getInt("wheel.size", 512)
  private[util] val timer = new io.netty.util.HashedWheelTimer(timerMillis.toLong, TimeUnit.MILLISECONDS, wheelSize)
  private val repeatTimers = new ConcurrentHashMap[Long, Timeout]()

  /**
   * Creates a TimerTask from a Function
   *
   * @param func Function to be tasked
   */
  private def task(func: () => Unit): TimerTask = new TimerTask() {
    def run(timeout: Timeout): Unit = func()
  }

  private def repeatFunc(id: Long, func: () => Unit, delay: Duration): () => Unit = () => {
    val to = timer.newTimeout(task(repeatFunc(id, func, delay)), delay.length, delay.unit)
    repeatTimers.put(id, to)
    func()
  }

  /**
   * Schedule an event.
   *
   * @param func Function to be scheduled
   * @param initialDelay Initial delay before first firing
   * @param delay Optional delay to be used if it is to be rescheduled (again)
   */
  def apply(func: () => Unit, initialDelay: Duration, delay: Option[Duration] = None): Action =
    delay match {
      case Some(d) => apply(func, initialDelay, d)
      case None => new Action(Some(timer.newTimeout(task(func), initialDelay.length, initialDelay.unit)))
    }

  /**
   * Schedule an event over and over again saving timeout reference.
   *
   * @param func Function to be scheduled
   * @param initialDelay Initial delay before first firing
   * @param delay Delay to be called after the first firing
   */
  def apply(func: () => Unit, initialDelay: Duration, delay: Duration): Action = {
    val action = new Action(None)
    val to = timer.newTimeout(task(repeatFunc(action.id, func, delay)), initialDelay.length, initialDelay.unit)
    repeatTimers.put(action.id, to)
    action
  }

  /**
   * Schedule an event once.
   *
   * @param func Function to be scheduled
   * @param initialDelay Initial delay before first firing
   */
  def once(func: () => Unit, initialDelay: Duration): Action = apply(func, initialDelay)

  /**
   * Schedule an event over and over again.
   *
   * @param func Function to be scheduled
   * @param initialDelay Initial delay before first firing
   * @param delay Delay to be called after the first firing
   */
  def again(func: () => Unit, initialDelay: Duration, delay: Duration): Action = apply(func, initialDelay, delay)

  /**
   * This is a proxy-class, which works around the rescheduling issue.
   * If a task is scheduled once, it will have Some(Timeout).
   * If it is to be scheduled more than once, it will have None, but
   * methods will work transparently through a reference in Schedule.repeatTimers.
   *
   * @param timeout Optional Timeout parameter only provided by Schedule.once
   */
  class Action(timeout: Option[Timeout]) {
    lazy val id = scala.util.Random.nextLong
    private def getTimeout() = timeout orElse Option(repeatTimers.get(id))

    /**
     * Cancel the scheduled event.
     */
    def cancel(): Unit = getTimeout match {
      case Some(t) =>
        t.cancel
        repeatTimers.remove(id)
      case None =>
    }

    /**
     * Get the according TimerTask.
     */
    def task(): Option[TimerTask] = getTimeout.map(_.task)
  }
}
