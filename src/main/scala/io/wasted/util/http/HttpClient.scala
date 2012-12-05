package io.wasted.util.http

import io.wasted.util.Logger

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
 * Netty HTTP Client Object to create HTTP Request Objects.
 */
object HttpClient {
  /**
   * Creates a HTTP Client which will call the given method with the returned HttpResponse.
   *
   * @param doneF Function which will handle the result
   * @param engine Optional SSLEngine
   */
  def apply(doneF: (Option[HttpResponse]) => Unit, engine: Option[SSLEngine]): HttpClient[Object] =
    this.apply(new HttpClientResponseAdapter(doneF), engine)

  /**
   * Creates a HTTP Client which implements the given Netty HandlerAdapter.
   *
   * @param handler Implementation of ChannelInboundMessageHandlerAdapter
   * @param engine Optional SSLEngine
   */
  def apply[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], engine: Option[SSLEngine]): HttpClient[T] =
    new HttpClient(handler, engine)
}

/**
 * Netty Response Adapter which is used for the doneF-Approach in HttpClient Object.
 *
 * @param doneF Function which will handle the result
 */
@ChannelHandler.Sharable
class HttpClientResponseAdapter(doneF: (Option[HttpResponse]) => Unit) extends ChannelInboundMessageHandlerAdapter[Object] with Logger {
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    ExceptionHandler(ctx, cause)
    doneF(None)
  }

  override def messageReceived(ctx: ChannelHandlerContext, msg: Object) {
    msg match {
      case a: HttpResponse => doneF(Some(a))
      case _ => doneF(None)
    }
  }
}

/**
 * Netty Http Client class which will do all of the Setup needed to make simple HTTP Requests.
 *
 * @param doneF Function which will handle the result
 */
class HttpClient[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], engine: Option[SSLEngine] = None) extends Logger {

  private def getPort(url: java.net.URL) = if (url.getPort == -1) url.getDefaultPort else url.getPort

  private def prepare(url: java.net.URL) = {
    val srv = new Bootstrap
    srv.group(new NioEventLoopGroup)
      .channel(classOf[NioSocketChannel])
      .remoteAddress(new InetSocketAddress(url.getHost, getPort(url)))
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
      .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, false)
      .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
      .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
      .handler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel) {
          val p = ch.pipeline()
          engine.foreach(e => p.addLast("ssl", new SslHandler(e)))
          p.addLast("codec", new HttpClientCodec)
          p.addLast("handler", handler)
        }
      })
  }

  /**
   * Run a GET-Request on the given URL.
   *
   * @param url What could this be?
   * @param headers The mysteries keep piling up!
   */
  def get(url: java.net.URL, headers: Map[String, String] = Map()) {
    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, url.getPath)
    req.setHeader(HttpHeaders.Names.HOST, url.getHost + ":" + getPort(url))
    req.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    headers.foreach(f => req.setHeader(f._1, f._2))

    val channel = prepare(url).connect().sync().channel()
    channel.write(req)
    channel.closeFuture().sync()
  }

  /**
   * Send a Post-Request on the given URL.
   *
   * @param url This is getting weird..
   * @param mime The MIME type the POST request
   * @param body ByteArray to be POSTed
   * @param headers
   */
  def post(url: java.net.URL, mime: String, body: Array[Byte] = Array(), headers: Map[String, String] = Map()) {
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
    channel.closeFuture().sync()
  }
}

