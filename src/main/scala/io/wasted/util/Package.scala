package io.wasted

import java.io.{ PrintWriter, StringWriter }

/**
 * Helpers
 */
package object util {

  implicit val hashingAlgo = HashingAlgo()
  implicit val hexingAlgo = HexingAlgo()
  implicit val cryptoCipher = CryptoCipher()

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

