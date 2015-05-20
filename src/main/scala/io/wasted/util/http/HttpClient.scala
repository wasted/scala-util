package io.wasted.util.http

import java.net.{ InetAddress, InetSocketAddress }

import com.twitter.util.{ Duration, Future }
import io.netty.buffer._
import io.netty.channel._
import io.netty.handler.codec.http._
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
    val readTimeout = "readTimeout"
    val writeTimeout = "writeTimeout"
    val handler = "handler"
  }
}

/**
 * wasted.io Scala Http Client
 * @param codec Http Codec
 * @param remote Remote Host and Port
 * @param hostConnectionLimit Number of open connections for this client. Defaults to 1
 * @param hostConnectionCoreSize Number of connections to keep open for this client. Defaults to 0
 * @param globalTimeout Global Timeout for the completion of a request
 * @param tcpConnectTimeout TCP Connect Timeout
 * @param connectTimeout Timeout for establishing the Service
 * @param requestTimeout Timeout for each request
 * @param tcpKeepAlive TCP KeepAlive. Defaults to false
 * @param reuseAddr Reuse-Address. Defaults to true
 * @param tcpNoDelay TCP No-Delay. Defaults to true
 * @param soLinger soLinger. Defaults to 0
 * @param retries On connection or timeouts, how often should we retry? Defaults to 0
 * @param eventLoop Netty Event-Loop
 */
case class HttpClient[T <: HttpObject](codec: NettyHttpCodec[HttpRequest, T] = NettyHttpCodec[HttpRequest, T](),
                                       remote: List[InetSocketAddress] = List.empty,
                                       hostConnectionLimit: Int = 1,
                                       hostConnectionCoreSize: Int = 0,
                                       globalTimeout: Option[Duration] = None,
                                       tcpConnectTimeout: Option[Duration] = None,
                                       connectTimeout: Option[Duration] = None,
                                       requestTimeout: Option[Duration] = None,
                                       tcpKeepAlive: Boolean = false,
                                       reuseAddr: Boolean = true,
                                       tcpNoDelay: Boolean = true,
                                       soLinger: Int = 0,
                                       retries: Int = 0,
                                       eventLoop: EventLoopGroup = Netty.eventLoop) extends NettyClientBuilder[HttpRequest, T] {
  def withSpecifics(codec: NettyHttpCodec[HttpRequest, T]) = copy[T](codec = codec)
  def withSoLinger(soLinger: Int) = copy[T](soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy[T](tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy[T](tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy[T](reuseAddr = reuseAddr)
  def withGlobalTimeout(globalTimeout: Duration) = copy[T](globalTimeout = Some(globalTimeout))
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy[T](tcpConnectTimeout = Some(tcpConnectTimeout))
  def withConnectTimeout(connectTimeout: Duration) = copy[T](connectTimeout = Some(connectTimeout))
  def withRequestTimeout(requestTimeout: Duration) = copy[T](requestTimeout = Some(requestTimeout))
  def withHostConnectionLimit(limit: Int) = copy[T](hostConnectionLimit = limit)
  def withHostConnectionCoresize(coreSize: Int) = copy[T](hostConnectionCoreSize = coreSize)
  def withRetries(retries: Int) = copy[T](retries = retries)
  def withEventLoop(eventLoop: EventLoopGroup) = copy[T](eventLoop = eventLoop)
  def connectTo(host: String, port: Int) = copy[T](remote = List(new InetSocketAddress(InetAddress.getByName(host), port)))
  def connectTo(hosts: List[InetSocketAddress]) = copy[T](remote = hosts)

  protected def getPortString(url: java.net.URI): String = if (url.getPort == -1) "" else ":" + url.getPort
  protected def getPort(url: java.net.URI): Int = if (url.getPort > 0) url.getPort else url.getScheme match {
    case "http" => 80
    case "https" => 443
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

