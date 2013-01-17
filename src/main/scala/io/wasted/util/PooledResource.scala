package io.wasted.util

import java.util.concurrent.LinkedTransferQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class PooledResource[T <: Any](newFunc: () => T, max: Int, timeout: Int = 5000) {
  private val size = new AtomicInteger(0)
  private val pool = new LinkedTransferQueue[T]

  def get(): Option[T] = Tryo(pool.poll) match {
    case Some(r: T) => Some(r)
    case _ => createOrBlock
  }

  private def createOrBlock: Option[T] = size.get match {
    case s: Int if s == max => block
    case _ => create
  }

  private def create: Option[T] = size.incrementAndGet match {
    case s: Int if s > max => size.decrementAndGet; get()
    case s: Int => Tryo(newFunc())
  }

  private def block: Option[T] = Tryo(pool.poll(timeout, TimeUnit.MILLISECONDS))

  def release(r: T): Unit = pool.offer(r)
}

