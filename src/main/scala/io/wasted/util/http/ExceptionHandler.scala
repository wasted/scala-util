package io.wasted.util.http

import io.netty.channel.ChannelHandlerContext

/**
 * Basic Netty Exception-handling with Logging capabilities.
 */
object ExceptionHandler {
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
    if (s.startsWith("java.nio.channels.ClosedChannelException") ||
      s.startsWith("java.io.IOException") ||
      s.startsWith("javax.net.ssl.SSLException") ||
      s.startsWith("java.lang.IllegalArgumentException")) return None

    if (ctx.channel.isOpen) ctx.channel.closeFuture().sync()
    Some(cause)
  }
}

