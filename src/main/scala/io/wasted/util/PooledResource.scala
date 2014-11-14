package io.wasted.util

import java.util.concurrent.{ LinkedTransferQueue, TimeUnit }
import java.util.concurrent.atomic.AtomicInteger

/**
 * Resource Pooling Class
 *
 * @param newFunc Function to create a new T type object
 * @param max Maximum number of objects that this pool will hold
 * @param timeout Timeout in milliseconds for blocking get operations
 */
class PooledResource[T <: Any](newFunc: () => T, max: Int, timeout: Int = 5000) {
  private val size = new AtomicInteger(0)
  private val pool = new LinkedTransferQueue[T]

  /* Get an object from the pool. */
  def get(): Option[T] = Tryo(pool.poll) match {
    case Some(r: T @unchecked) => Some(r)
    case _ => createOrBlock
  }

  private def createOrBlock: Option[T] = size.get match {
    case s: Int if s == max => block
    case _ => create
  }

  private def create: Option[T] = size.incrementAndGet match {
    case s: Int if s > max =>
      size.decrementAndGet; get()
    case s: Int => Tryo(newFunc())
  }

  private def block: Option[T] = Tryo(pool.poll(timeout, TimeUnit.MILLISECONDS))

  /* Release an object back into the pool. */
  def release(r: T): Unit = pool.offer(r)
}

