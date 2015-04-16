package io.wasted.util.http

import io.netty.buffer._
import io.netty.handler.codec.http.HttpHeaders.Names._
import io.netty.handler.codec.http.HttpHeaders._
import io.netty.handler.codec.http.HttpVersion._
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil

/**
 * Responder class used in our server applications
 * @param token HTTP Server token
 * @param allocator ByteBuf Allocator
 */
class HttpResponder(token: String, allocator: ByteBufAllocator = UnpooledByteBufAllocator.DEFAULT) {
  def apply(
    status: HttpResponseStatus,
    body: Option[String] = None,
    mime: Option[String] = None,
    keepAlive: Boolean = true,
    headers: Map[String, String] = Map()): FullHttpResponse = {
    val res = body.map { body =>
      val bytes = body.getBytes(CharsetUtil.UTF_8).toArray
      val content = allocator.ioBuffer(bytes.length, bytes.length)
      content.writeBytes(bytes).slice()
      val res = new DefaultFullHttpResponse(HTTP_1_1, status, content)
      setContentLength(res, content.readableBytes())
      res
    } getOrElse {
      val res = new DefaultFullHttpResponse(HTTP_1_1, status)
      setContentLength(res, 0)
      res
    }

    mime.map(res.headers.set(CONTENT_TYPE, _))
    res.headers.set(SERVER, token)
    headers.foreach { h => res.headers.set(h._1, h._2) }

    res.headers.set(CONNECTION, if (!keepAlive) Values.CLOSE else Values.KEEP_ALIVE)
    res
  }
}
