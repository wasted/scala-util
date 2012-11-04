package io.wasted.util

/**
 * This is a simple try/catch wrapped in an Option.
 */
object Tryo {
  def apply[T](f: => T): Option[T] = try { Some(f) } catch { case _ => None }
  def apply[T](f: => T, fallback: T): T = try { f } catch { case _ => fallback }
}