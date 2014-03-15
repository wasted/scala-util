package io.wasted.util.http

import io.netty.buffer._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.HttpHeaders._
import io.netty.handler.codec.http.HttpHeaders.Names._
import io.netty.handler.codec.http.HttpVersion._

/**
 * Responder class used in our server applications
 * @param token HTTP Server token
 * @param pooledBuffer If we use pooled netty buffers
 * @param heapBuffer or heap buffers (pooled wins over this)
 */
class HttpResponder(token: String, pooledBuffer: Boolean, heapBuffer: Boolean) {
  def apply(
    status: HttpResponseStatus,
    body: Option[String] = None,
    mime: Option[String] = None,
    keepAlive: Boolean = true,
    headers: Map[String, String] = Map()): FullHttpResponse = {
    val res = body match {
      case Some(body) =>
        val bytes = body.getBytes("UTF-8").toArray
        val content = pooledBuffer match {
          case true =>
            val pooledBuf = heapBuffer match {
              case true => PooledByteBufAllocator.DEFAULT.heapBuffer(bytes.length, bytes.length).slice()
              case flase => PooledByteBufAllocator.DEFAULT.directBuffer(bytes.length, bytes.length).slice()
            }
            pooledBuf.setBytes(0, bytes)
            pooledBuf
          case false => Unpooled.wrappedBuffer(bytes).slice()
        }
        val res = new DefaultFullHttpResponse(HTTP_1_1, status, content)
        setContentLength(res, content.readableBytes())
        res
      case None =>
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
