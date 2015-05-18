package io.wasted.util.http

import com.twitter.conversions.time._
import com.twitter.util.{ Duration, Future, Promise }
import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.timeout._
import io.wasted.util._

object HttpClient {

  /**
   * These can be used to modify the pipeline afterwards without having to guess their names
   */
  object Handlers {
    val ssl = "ssl"
    val codec = "codec"
    val aggregator = "aggregator"
    val decompressor = "decompressor"
    val timeout = "timeout"
    val handler = "handler"
  }
}

/**
 * wasted.io Scala Http Client
 * @param codec Http Codec
 * @param remote Remote Host and Port
 * @param tcpConnectTimeout TCP Connect Timeout
 * @param tcpKeepAlive TCP KeepAlive
 * @param reuseAddr Reuse-Address
 * @param tcpNoDelay TCP No-Delay
 * @param soLinger soLinger
 * @param eventLoop Netty Event-Loop
 */
final case class HttpClient[T <: HttpObject](codec: HttpCodec[T] = HttpCodec[T](),
                                             remote: Option[(String, Int)] = None,
                                             tcpConnectTimeout: Duration = 5.seconds,
                                             tcpKeepAlive: Boolean = false,
                                             reuseAddr: Boolean = true,
                                             tcpNoDelay: Boolean = true,
                                             soLinger: Int = 0,
                                             eventLoop: EventLoopGroup = Netty.eventLoop) {
  def withSpecifics(codec: HttpCodec[T]) = copy[T](codec = codec)
  def withSoLinger(soLinger: Int) = copy[T](soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy[T](tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy[T](tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy[T](reuseAddr = reuseAddr)
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy[T](tcpConnectTimeout = tcpConnectTimeout)

  def withEventLoop(eventLoop: EventLoopGroup) = copy[T](eventLoop = eventLoop)
  def connectTo(host: String, port: Int) = copy[T](remote = Some(host, port))

  private lazy val srv = new Bootstrap
  private lazy val bootstrap = {
    val handler = new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        codec.sslCtx.foreach(e => p.addLast(HttpServer.Handlers.ssl, e.newHandler(ch.alloc())))
        val maxInitialBytes = codec.maxInitialLineLength.inBytes.toInt
        val maxHeaderBytes = codec.maxHeaderSize.inBytes.toInt
        val maxChunkSize = codec.maxChunkSize.inBytes.toInt
        p.addLast(HttpClient.Handlers.codec, new HttpClientCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize))
        if (codec.chunking && !codec.chunked) {
          p.addLast(HttpClient.Handlers.aggregator, new HttpObjectAggregator(codec.maxChunkSize.inBytes.toInt))
        }
        if (codec.decompression) {
          p.addLast(HttpClient.Handlers.decompressor, new HttpContentDecompressor())
        }
      }
    }
    srv.group(eventLoop)
      .channel(classOf[NioSocketChannel])
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, tcpNoDelay)
      .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, tcpKeepAlive)
      .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, reuseAddr)
      .option[java.lang.Integer](ChannelOption.SO_LINGER, soLinger)
      .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, tcpConnectTimeout.inMillis.toInt)
      .handler(handler)
  }

  private def getPortString(url: java.net.URI): String = if (url.getPort == -1) "" else ":" + url.getPort
  private def getPort(url: java.net.URI): Int = if (url.getPort > 0) url.getPort else url.getScheme match {
    case "http" => 80
    case "https" => 443
  }

  /**
   * Write a Netty HttpRequest directly through to the given URI.
   * The request to generate the response should be used to prepare
   * the request only once the connection is established.
   * This reduces the context-switching for allocation/deallocation
   * on failed connects.
   *
   * @param url What could this be?
   * @param req Netty FullHttpRequest
   */
  def write(url: java.net.URI, req: () => HttpRequest): Future[T] = {
    val promise = Promise[T]()

    val connected = remote match {
      case Some((host, port)) => bootstrap.connect(host, port)
      case _ => bootstrap.clone().connect(url.getHost, getPort(url))
    }
    connected.addListener { cf: ChannelFuture =>
      if (!cf.isSuccess) promise.setException(cf.cause())
      else {
        cf.channel.pipeline.addFirst(HttpClient.Handlers.timeout, new ReadTimeoutHandler(codec.readTimeout.inMillis.toInt) {
          override def readTimedOut(ctx: ChannelHandlerContext) {
            ctx.channel.close
            promise.setException(new IllegalStateException("Read timed out"))
          }
        })
        cf.channel.pipeline.addLast(HttpClient.Handlers.handler, new SimpleChannelInboundHandler[T] {
          override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ExceptionHandler(ctx, cause)
            promise.setException(cause)
            ctx.channel.close
          }

          def channelRead0(ctx: ChannelHandlerContext, msg: T) {
            msg match {
              case msg: ByteBufHolder => msg.content.retain()
              case _ =>
            }
            promise.setValue(msg)
          }
        })

        val request = req()
        val ka = if (tcpKeepAlive && HttpHeaders.isKeepAlive(request))
          HttpHeaders.Values.KEEP_ALIVE else HttpHeaders.Values.CLOSE
        request.headers().set(HttpHeaders.Names.CONNECTION, ka)

        cf.channel.writeAndFlush(request)
      }
    }
    promise
  }

  /**
   * Run a GET-Request on the given URI.
   *
   * @param url What could this be?
   * @param headers The mysteries keep piling up!
   */
  def get(url: java.net.URI, headers: Map[String, String] = Map()): Future[T] = {
    val path = if (url.getQuery == null) url.getPath else url.getPath + "?" + url.getQuery
    val req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path)
    req.headers.set(HttpHeaders.Names.HOST, url.getHost + getPortString(url))
    req.headers.set(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE)
    headers.foreach(f => req.headers.set(f._1, f._2))
    write(url, () => req)
  }

  /**
   * Send a PUT/POST-Request on the given URI with body.
   *
   * @param url This is getting weird..
   * @param mime The MIME type of the request
   * @param body ByteArray to be send
   * @param headers I don't like to explain trivial stuff
   * @param method HTTP Method to be used
   */
  def post(url: java.net.URI, mime: String, body: Seq[Byte] = Seq(), headers: Map[String, String] = Map(), method: HttpMethod): Future[T] = {
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
  def thruput(url: java.net.URI, auth: java.util.UUID, sign: java.util.UUID, payload: String): Future[T] = {
    val headers = Map("X-Io-Auth" -> auth.toString, "X-Io-Sign" -> io.wasted.util.Hashing.sign(sign.toString, payload))
    post(url, "application/json", payload.map(_.toByte), headers, HttpMethod.PUT)
  }
}

