package io.wasted.util.http

import io.wasted.util.Logger

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket._
import io.netty.channel.socket.nio._
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler

import javax.net.ssl.SSLEngine
import java.net.InetSocketAddress

/**
 * Netty HTTP Client Object to create HTTP Request Objects.
 */
object HttpClient {
  /**
   * Creates a HTTP Client which will call the given method with the returned HttpResponse.
   *
   * @param engine Optional SSLEngine
   */
  def apply(timeout: Int = 20, engine: Option[SSLEngine] = None): HttpClient[Object] = {
    val doneF = (x: Option[io.netty.handler.codec.http.HttpResponse]) => {}
    this.apply(new HttpClientResponseAdapter(doneF), timeout, engine)
  }

  /**
   * Creates a HTTP Client which will call the given method with the returned HttpResponse.
   *
   * @param doneF Function which will handle the result
   * @param engine Optional SSLEngine
   */
  def apply(doneF: (Option[HttpResponse]) => Unit, timeout: Int, engine: Option[SSLEngine]): HttpClient[Object] =
    this.apply(new HttpClientResponseAdapter(doneF), timeout, engine)

  /**
   * Creates a HTTP Client which implements the given Netty HandlerAdapter.
   *
   * @param handler Implementation of ChannelInboundMessageHandlerAdapter
   * @param engine Optional SSLEngine
   */
  def apply[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], timeout: Int, engine: Option[SSLEngine]): HttpClient[T] =
    new HttpClient(handler, timeout, engine)
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
class HttpClient[T <: Object](handler: ChannelInboundMessageHandlerAdapter[T], timeout: Int = 20, engine: Option[SSLEngine] = None) extends Logger {

  lazy val srv = new Bootstrap
  lazy val bootstrap = srv.group(new NioEventLoopGroup)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, false)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        engine.foreach(e => p.addLast("ssl", new SslHandler(e)))
        p.addLast("codec", new HttpClientCodec)
        p.addLast("handler", handler)
      }
    })

  private def getPort(url: java.net.URL) = if (url.getPort == -1) url.getDefaultPort else url.getPort

  private def prepare(url: java.net.URL) = {
    bootstrap.duplicate.remoteAddress(new InetSocketAddress(url.getHost, getPort(url)))
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
    channel.closeFuture()
  }

  /**
   * Send a PUT/POST-Request on the given URL with body.
   *
   * @param url This is getting weird..
   * @param mime The MIME type of the request
   * @param body ByteArray to be send
   * @param method HTTP Method to be used
   * @param headers
   */
  def post(url: java.net.URL, mime: String, body: Seq[Byte] = Seq(), headers: Map[String, String] = Map(), method: HttpMethod) {
    val content = Unpooled.wrappedBuffer(body.toArray)
    val req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, method, url.getPath)
    req.setHeader(HttpHeaders.Names.HOST, url.getHost + ":" + getPort(url))
    req.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    req.setHeader(HttpHeaders.Names.CONTENT_TYPE, mime)
    req.setHeader(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes)
    headers.foreach(f => req.setHeader(f._1, f._2))
    req.setContent(content)

    val channel = prepare(url).connect().sync().channel()
    channel.write(req)
    channel.closeFuture()
  }

  def thruput(url: java.net.URL, auth: java.util.UUID, sign: java.util.UUID, payload: String) {
    val headers = Map("X-Io-Auth" -> auth.toString, "X-Io-Sign" -> io.wasted.util.Hashing.sign(sign.toString, payload))
    post(url, "application/json", payload.map(_.toByte), headers, HttpMethod.PUT)
  }

  def close() {
    srv.shutdown()
  }
}

