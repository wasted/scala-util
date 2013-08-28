package io.wasted.util.http

import io.wasted.util._

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout._

import java.security.KeyStore
import java.net.InetSocketAddress
import java.io.{ File, FileInputStream }
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.{ SSLEngine, SSLContext }
import scala.concurrent._
import scala.util.Success
import io.netty.util.ReferenceCounted

/**
 * Netty HTTP Client Object to create HTTP Request Objects.
 */
object HttpClient {

  /**
   * Creates a HTTP Client which will call the given method with the returned HttpObject.
   *
   * @param chunked Get responses in chunks
   * @param timeout Connect timeout in seconds
   * @param engine Optional SSLEngine
   */
  def apply(chunked: Boolean = true, timeout: Int = 5, engine: Option[() => SSLEngine] = None): HttpClient = {
    new HttpClient(chunked, timeout, engine)
  }

  /* Default Client SSLContext. */
  lazy val defaultClientSSLContext: SSLContext = {
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, null, null)
    ctx
  }

  /**
   * Get a SSLEngine for clients for given host and port.
   *
   * @param host Hostname
   * @param port Port
   */
  def getSSLClientEngine(host: String, port: Int): SSLEngine = {
    val ce = defaultClientSSLContext.createSSLEngine(host, port)
    ce.setUseClientMode(true)
    ce
  }
}

/**
 * Netty Response Adapter which uses scala Futures.
 *
 * @param promise Promise for type T object
 * @param T Type of the expected Netty Result
 */
class HttpClientResponseAdapter(promise: Promise[FullHttpResponse]) extends SimpleChannelInboundHandler[FullHttpResponse] {
  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    ExceptionHandler(ctx, cause)
    promise.failure(cause)
    ctx.channel.close
  }

  def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
    msg.content.retain()
    promise.complete(Success(msg))
  }
}

/**
 * Netty Http Client class which will do all of the Setup needed to make simple HTTP Requests.
 *
 * @param chunked Get responses in chunks
 * @param timeout Connect timeout in seconds
 * @param engine Optional SSLEngine
 * @param persistent Optional Host Address we're directing all requests to, regardless of their DNS lookup
 */
class HttpClient(chunked: Boolean = true, timeout: Int = 5, engine: Option[() => SSLEngine] = None, persistent: Option[(String, Int)] = None) {

  private var disabled = false
  private lazy val srv = new Bootstrap
  private lazy val bootstrap = srv.group(Netty.eventLoop)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, false)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        engine.foreach(e => p.addLast("ssl", new SslHandler(e())))
        p.addLast("codec", new HttpClientCodec)
        if (!chunked) p.addLast("aggregator", new HttpObjectAggregator(1024 * 1024))
      }
    })

  private def getPort(url: java.net.URL): Int = if (url.getPort == -1) url.getDefaultPort else url.getPort
  private def getPortString(url: java.net.URL): String = if (url.getPort == -1) "" else ":" + url.getPort

  /**
   * Write a Netty HttpRequest directly through to the given URL.
   *
   * @param url What could this be?
   * @param req Netty FullHttpRequest
   */
  def write(url: java.net.URL, req: () => FullHttpRequest): Future[FullHttpResponse] = {
    val promise = Promise[FullHttpResponse]()
    if (disabled) {
      promise.failure(new IllegalStateException("HttpClient is already shutdown."))
    } else {
      val connected = persistent match {
        case Some((host, port)) => bootstrap.connect(host, port)
        case _ => bootstrap.clone().connect(url.getHost, getPort(url))
      }
      connected.addListener(new ChannelFutureListener() {
        override def operationComplete(cf: ChannelFuture) {
          if (!cf.isSuccess) promise.failure(cf.cause())
          else {
            cf.channel.pipeline.addFirst("timeout", new ReadTimeoutHandler(timeout) {
              override def readTimedOut(ctx: ChannelHandlerContext) {
                ctx.channel.close
                promise.failure(new IllegalStateException("Did not get response in time"))
              }
            })
            cf.channel.pipeline.addLast("handler", new HttpClientResponseAdapter(promise))
            cf.channel.writeAndFlush(req())
          }
        }
      })
    }
    promise.future
  }

  /**
   * Run a GET-Request on the given URL.
   *
   * @param url What could this be?
   * @param headers The mysteries keep piling up!
   */
  def get(url: java.net.URL, headers: Map[String, String] = Map()): Future[FullHttpResponse] = {
    val path = if (url.getQuery == null) url.getPath else url.getPath + "?" + url.getQuery
    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)
    req.headers.set(HttpHeaders.Names.HOST, url.getHost + getPortString(url))
    req.headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    headers.foreach(f => req.headers.set(f._1, f._2))
    write(url, () => req)
  }

  /**
   * Send a PUT/POST-Request on the given URL with body.
   *
   * @param url This is getting weird..
   * @param mime The MIME type of the request
   * @param body ByteArray to be send
   * @param headers I don't like to explain trivial stuff
   * @param method HTTP Method to be used
   */
  def post(url: java.net.URL, mime: String, body: Seq[Byte] = Seq(), headers: Map[String, String] = Map(), method: HttpMethod): Future[FullHttpResponse] = {
    write(url, () => {
      val content = Unpooled.wrappedBuffer(body.toArray)
      val path = if (url.getQuery == null) url.getPath else url.getPath + "?" + url.getQuery
      val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, content)
      req.headers.set(HttpHeaders.Names.HOST, url.getHost + getPortString(url))
      req.headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
      req.headers.set(HttpHeaders.Names.CONTENT_TYPE, mime)
      req.headers.set(HttpHeaders.Names.CONTENT_LENGTH, content.readableBytes)
      headers.foreach(f => req.headers.set(f._1, f._2))
      req
    })
  }

  /**
   * Send a message to thruput.io endpoint.
   *
   * @param url thruput.io Endpoint
   * @param auth Authentication Key for thruput.io platform
   * @param sign Signing Key for thruput.io platform
   * @param payload Payload to be sent
   */
  def thruput(url: java.net.URL, auth: java.util.UUID, sign: java.util.UUID, payload: String): Future[FullHttpResponse] = {
    val headers = Map("X-Io-Auth" -> auth.toString, "X-Io-Sign" -> io.wasted.util.Hashing.sign(sign.toString, payload))
    post(url, "application/json", payload.map(_.toByte), headers, HttpMethod.PUT)
  }

  /**
   * Shutdown this client.
   */
  def shutdown() {
    disabled = true
  }
}

