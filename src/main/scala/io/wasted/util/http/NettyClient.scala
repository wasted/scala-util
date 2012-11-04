package io.wasted.util.http

import io.wasted.util._
import io.wasted.util.ssl._

import io.netty.util._
import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.group._
import io.netty.channel.socket._
import io.netty.channel.socket.nio._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.HttpMethod._
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.ssl.SslHandler

import javax.net.ssl.SSLEngine
import java.net.InetSocketAddress
import java.util.concurrent._

/**
 * Netty HTTP Client to handle HTTP Requests
 */
class NettyClient[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], engine: Option[SSLEngine] = None) extends Logger {
  val srv = new Bootstrap

  private def getPort(url: java.net.URL) = if (url.getPort == -1) url.getDefaultPort else url.getPort

  private def prepare(url: java.net.URL) = {
    srv.group(new NioEventLoopGroup)
      .channel(new NioSocketChannel getClass)
      .remoteAddress(new InetSocketAddress(url.getHost, getPort(url)))
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
      .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, false)
      .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
      .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
      .handler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel) {
          val p = ch.pipeline()
          engine match {
            case Some(e) => p.addLast("ssl", new SslHandler(e))
            case _ =>
          }
          p.addLast("codec", new HttpClientCodec)
          p.addLast("handler", handler)
        }
      })
  }

  def get(url: java.net.URL, headers: Map[String, String] = Map()) {
    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, url.getPath)
    req.setHeader(HttpHeaders.Names.HOST, url.getHost + ":" + getPort(url))
    req.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    headers.foreach(f => req.setHeader(f._1, f._2))

    val channel = prepare(url).connect().sync().channel()
    channel.write(req)
    channel.closeFuture().sync()
  }

  def post(url: java.net.URL, mime: String, body: Array[Byte] = Array(), headers: Map[String, String] = Map()) {
    try {
      val content = Unpooled.copiedBuffer(body)
      val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, url.getPath)
      req.setHeader(HttpHeaders.Names.HOST, url.getHost + ":" + getPort(url))
      req.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
      req.setHeader(HttpHeaders.Names.CONTENT_TYPE, mime)
      req.setHeader(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes)
      headers.foreach(f => req.setHeader(f._1, f._2))
      req.setContent(content)

      val channel = prepare(url).connect().sync().channel()
      channel.write(req).sync()
      //channel.closeFuture().sync()
    } catch {
      case e => e.printStackTrace
    }
  }

  def shutdown() {
    srv.shutdown()
  }
}

