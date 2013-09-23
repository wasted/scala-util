package io.wasted.util.http

import io.netty.channel.ChannelHandlerContext

/**
 * Basic Netty Exception-handling with Logging capabilities.
 */
object ExceptionHandler {
  /**
   * Precompiled Patterns for performance reasons.
   * Filters unimportant/low level exceptions.
   */
  private val unimportant = List(
    "java.nio.channels.ClosedChannelException.*".r,
    "io.netty.handler.codec.CorruptedFrameException.*".r,
    "java.io.IOException.*".r,
    "javax.net.ssl.SSLException.*".r,
    "java.lang.IllegalArgumentException.*".r)

  /**
   * Bad client = closed connection, malformed requests, etc.
   *
   * Do nothing if the exception is one of the following:
   * java.io.IOException: Connection reset by peer
   * java.io.IOException: Broken pipe
   * java.nio.channels.ClosedChannelException: null
   * javax.net.ssl.SSLException: not an SSL/TLS record (Use http://... URL to connect to HTTPS server)
   * java.lang.IllegalArgumentException: empty text (Use http://... URL to connect to HTTPS server)
   */
  def apply(ctx: ChannelHandlerContext, cause: Throwable): Option[Throwable] = {
    val s = cause.toString
    if (unimportant.exists(_.findFirstIn(s).isDefined)) None
    else {
      if (ctx.channel.isOpen) ctx.channel.close()
      Some(cause)
    }
  }
}
