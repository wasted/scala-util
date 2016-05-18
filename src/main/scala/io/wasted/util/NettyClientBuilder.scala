package io.wasted.util

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

import com.twitter.util._
import io.netty.bootstrap.Bootstrap
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.util.ReferenceCountUtil

trait NettyClientBuilder[Req, Resp] extends Logger {
  def codec: NettyCodec[Req, Resp]
  def remote: List[InetSocketAddress]
  //def hostConnectionLimit: Int
  //def hostConnectionCoreSize: Int
  def globalTimeout: Option[Duration]
  def tcpConnectTimeout: Option[Duration]
  def connectTimeout: Option[Duration]
  def requestTimeout: Option[Duration]
  def tcpKeepAlive: Boolean
  def reuseAddr: Boolean
  def tcpNoDelay: Boolean
  def soLinger: Int
  def retries: Int
  def eventLoop: EventLoopGroup

  implicit val timer = WheelTimer.twitter
  protected[this] lazy val requestCounter = new AtomicLong()
  protected[this] lazy val clnt = new Bootstrap
  protected[this] lazy val bootstrap = {
    // produce a warnings if timeouts do not add up
    val reqT = requestTimeout.map(_ * (retries + 1))
    val outterTotalT: Iterable[Duration] = List(reqT, connectTimeout).flatten
    if (outterTotalT.nonEmpty && globalTimeout.exists(_ < outterTotalT.reduceRight(_ + _))) {
      val logger = Logger(getClass.getCanonicalName)
      val str = "GlobalTimeout is lower than ConnectTimeout + RequestTimeout * (Retries + 1)"
      logger.warn(str + ". See debug for Trace")
      logger.debug(new IllegalArgumentException(str))
    }

    val innerReadReqT = codec.readTimeout.map(_ * (retries + 1))
    val innerReadT: Iterable[Duration] = List(reqT, innerReadReqT).flatten
    if (innerReadT.nonEmpty && globalTimeout.exists(_ < innerReadT.reduceRight(_ + _))) {
      val logger = Logger(getClass.getCanonicalName)
      val str = "GlobalTimeout is lower than ConnectTimeout + ReadTimeout * (Retries + 1)"
      logger.warn(str + ". See debug for Trace")
      logger.debug(new IllegalArgumentException(str))
    }

    val innerWriteReqT = codec.writeTimeout.map(_ * (retries + 1))
    val innerWriteT: Iterable[Duration] = List(reqT, innerWriteReqT).flatten
    if (innerWriteT.nonEmpty && globalTimeout.exists(_ < innerWriteT.reduceRight(_ + _))) {
      val logger = Logger(getClass.getCanonicalName)
      val str = "GlobalTimeout is lower than ConnectTimeout + WriteTimeout * (Retries + 1)"
      logger.warn(str + ". See debug for Trace")
      logger.debug(new IllegalArgumentException(str))
    }
    val handler = new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel): Unit = codec.clientPipeline(ch)
    }
    val baseGrp = clnt.group(eventLoop)
      .channel(classOf[NioSocketChannel])
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, tcpNoDelay)
      .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, tcpKeepAlive)
      .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, reuseAddr)
      .option[java.lang.Integer](ChannelOption.SO_LINGER, soLinger)

    val tcpCtGrp = tcpConnectTimeout.map { tcpCT =>
      baseGrp.option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, tcpCT.inMillis.toInt)
    }.getOrElse(baseGrp)

    tcpCtGrp.handler(handler)
  }

  /**
   * Write a Request directly through to the given URI.
   * The request to generate the response should be used to prepare
   * the request only once the connection is established.
   * This reduces the context-switching for allocation/deallocation
   * on failed connects.
   *
   * @param uri What could this be?
   * @param req Request object
   * @return Future Resp
   */
  def write(uri: java.net.URI, req: Req): Future[Resp] = write(uri.toString, req)

  /**
   * Write a Request directly through to the given URI.
   * The request to generate the response should be used to prepare
   * the request only once the connection is established.
   * This reduces the context-switching for allocation/deallocation
   * on failed connects.
   *
   * @param uri What could this be?
   * @param req Request object
   * @return Future Resp
   */
  def write(uri: String, req: Req): Future[Resp] = {
    val result = run(uri, req)
    globalTimeout.map(result.raiseWithin).getOrElse(result)
  }

  /**
   * Run the request against one of the specified remotes
   * @param uri What could this be?
   * @param req Request object
   * @param counter Request counter
   * @return Future Resp
   */
  protected[this] def run(uri: String, req: Req, counter: Int = 0): Future[Resp] = {
    // if it is the first time fire this request, we retain it twice for retries
    ReferenceCountUtil.retain(req, if (counter == 0) 1 else 2)
    getConnection(uri).flatMap { chan =>
      val resp = codec.clientConnected(chan, req)
      requestTimeout.map(resp.raiseWithin).getOrElse(resp).onFailure {
        case t: Throwable => resp.map(ReferenceCountUtil.release(_)) // release on failure
      }
    }.rescue {
      case t: Throwable if counter <= retries =>
        run(uri, req, counter + 1)
      case t: Throwable => Future.exception(t)
    }.ensure {
      // clean up the old retain
      if (counter == 0) ReferenceCountUtil.release(req)
    }
  }

  /**
   * Get a connection from the pool and regard the hostConnectionLimit
   * @param uri URI we want to connect to
   * @return Future Channel
   */
  protected[this] def getConnection(uri: String): Future[Channel] = {
    // TODO this is not implemented yet, looking for a nice way to keep track of connections
    connect(uri)
  }

  /**
   * Connects to the given URI and returns a Channel using a round-robin remote selection
   * @param uri URI we want to connect to
   * @return Future Channel
   */
  protected[this] def connect(uri: String): Future[Channel] = {
    val connectPromise = Promise[Channel]()

    val safeUri = new java.net.URI(uri.split("/", 4).take(3).mkString("/"))

    val connected = remote match {
      case Nil => bootstrap.clone().connect(safeUri.getHost, getPort(safeUri))
      case hosts =>
        // round robin connection
        val sock = hosts((requestCounter.incrementAndGet() % hosts.length).toInt)
        bootstrap.connect(sock)
    }
    connected.addListener { cf: ChannelFuture =>
      if (!cf.isSuccess) connectPromise.setException(cf.cause())
      else connectPromise.setValue(cf.channel)
    }
    connectTimeout.map(connectPromise.raiseWithin).getOrElse(connectPromise)
  }

  /**
   * Gets the port for the given URI.
   * @param uri The URI where we want the Port number for
   * @return Port Number
   */
  protected[this] def getPort(uri: java.net.URI): Int
}
