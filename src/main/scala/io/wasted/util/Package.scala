package io.wasted

import java.io.{ PrintWriter, StringWriter }

import com.twitter.util.{ Duration => TD }
import io.netty.channel.{ ChannelFuture, ChannelFutureListener }

import scala.concurrent.duration.{ Duration => SD }

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

  implicit val durationScala2Twitter: SD => TD = (sd) => TD(sd.length, sd.unit)
  implicit val durationTwitter2Scala: TD => SD = (td) => SD(td.inTimeUnit._1, td.inTimeUnit._2)

  implicit val channelFutureListener: (ChannelFuture => Any) => ChannelFutureListener = { pf =>
    new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = pf(f)
    }
  }
}

