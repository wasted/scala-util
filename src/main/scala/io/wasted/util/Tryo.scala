package io.wasted.util

import scala.util.Success

/**
 * This is a simple try/catch wrapped in an Option.
 */
object Tryo {
  def apply[T](f: => T): Option[T] = scala.util.Try(f) match { case Success(x) => Some(x) case _ => None }
  def apply[T](f: => T, fallback: T): T = scala.util.Try(f) getOrElse fallback
}
