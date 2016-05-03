package io.wasted.util.http

import io.netty.buffer._
import io.netty.handler.codec.http.HttpVersion._
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil

/**
 * Responder class used in our server applications
 * @param token HTTP Server token
 * @param allocator ByteBuf Allocator
 */
class HttpResponder(token: String, allocator: ByteBufAllocator = PooledByteBufAllocator.DEFAULT) {
  def apply(
    status: HttpResponseStatus,
    body: Option[String] = None,
    mime: Option[String] = None,
    keepAlive: Boolean = true,
    headers: Map[String, String] = Map()): FullHttpResponse = {
    val res = body.map { body =>
      val bytes = body.getBytes(CharsetUtil.UTF_8)
      val content = allocator.ioBuffer(bytes.length, bytes.length)
      content.writeBytes(bytes).slice()
      val res = new DefaultFullHttpResponse(HTTP_1_1, status, content)
      HttpUtil.setContentLength(res, content.readableBytes())
      res
    } getOrElse {
      val res = new DefaultFullHttpResponse(HTTP_1_1, status)
      HttpUtil.setContentLength(res, 0)
      res
    }

    mime.map(res.headers.set(HttpHeaderNames.CONTENT_TYPE, _))
    res.headers.set(HttpHeaderNames.SERVER, token)
    headers.foreach { h => res.headers.set(h._1, h._2) }

    res.headers.set(HttpHeaderNames.CONNECTION, if (!keepAlive) HttpHeaderValues.CLOSE else HttpHeaderValues.KEEP_ALIVE)
    res
  }
}
