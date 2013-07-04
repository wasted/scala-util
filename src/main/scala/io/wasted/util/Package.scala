package io.wasted

import java.io.{ StringWriter, PrintWriter }

/**
 * Helpers
 */
package object util {

  /**
   * Transforms StackTraces into a String using StringWriter.
   */
  def stackTraceToString(throwable: Throwable) = {
    val w = new StringWriter
    throwable.printStackTrace(new PrintWriter(w))
    w.toString
  }

  implicit val implicitStackTraceToString = stackTraceToString _

}

