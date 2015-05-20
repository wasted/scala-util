package io.wasted.util

import java.net.InetSocketAddress
import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }

import com.twitter.util._
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.{ ByteBuf, ByteBufHolder }
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel

trait NettyClientBuilder[Req, Resp] {
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
   * Open a connection to the given URI.
   * The request to generate the response should be used to prepare
   * the request only once the connection is established.
   * This reduces the context-switching for allocation/deallocation
   * on failed connects.
   *
   * @param uri What could this be?
   * @return Future Channel
   */
  def open(uri: java.net.URI, req: Req): Future[Resp] = {
    // we start at -1 for the first and not-retried-request
    val counter = new AtomicInteger()

    def run(): Future[Resp] = {
      val count = counter.incrementAndGet()
      val result = getConnection(uri).flatMap { chan =>
        val resp = codec.clientConnected(chan, req)
        requestTimeout.map(resp.raiseWithin).getOrElse(resp)
      }

      if (count <= retries + 1) result.rescue {
        case t: Throwable => run()
      }
      else result
    }

    val result = run()

    globalTimeout.map(result.raiseWithin).getOrElse(result)
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
  def write(uri: java.net.URI, req: () => Req): Future[Resp] = {
    // we start at -1 for the first and not-retried-request
    val counter = new AtomicInteger()
    val request = req()
    // If it's a bytebuffer, we save it for later use
    req match {
      case bb: ByteBuf => bb.retain()
      case bbh: ByteBufHolder => bbh.retain()
      case _ =>
    }

    val result = run(uri, request, counter)

    globalTimeout.map(result.raiseWithin).getOrElse(result).ensure {
      // release the buffer after we're done
      request match {
        case bb: ByteBuf => bb.release()
        case bbh: ByteBufHolder => bbh.release()
        case _ =>
      }
    }
  }

  /**
   * Run the request against one of the specified remotes
   * @param uri What could this be?
   * @param req Request object
   * @param counter Request counter
   * @return Future Resp
   */
  protected[this] def run(uri: java.net.URI, req: Req, counter: AtomicInteger): Future[Resp] = {
    // If it's a bytebuffer, retain it for the outbound handler
    req match {
      case bb: ByteBuf => bb.retain()
      case bbh: ByteBufHolder => bbh.retain()
      case _ =>
    }

    val count = counter.incrementAndGet()
    val result = getConnection(uri).flatMap { chan =>
      val resp = codec.clientConnected(chan, req)
      requestTimeout.map(resp.raiseWithin).getOrElse(resp)
    }

    if (count <= retries + 1) result.rescue {
      case t: Throwable =>
        run(uri, req, counter)
    }
    else result
  }

  /**
   * Get a connection from the pool and regard the hostConnectionLimit
   * @param uri URI we want to connect to
   * @return Future Channel
   */
  protected[this] def getConnection(uri: java.net.URI): Future[Channel] = {
    // TODO this is not implemented yet, looking for a nice way to keep track of connections
    connect(uri)
  }

  /**
   * Connects to the given URI and returns a Channel using a round-robin remote selection
   * @param uri URI we want to connect to
   * @return Future Channel
   */
  protected[this] def connect(uri: java.net.URI): Future[Channel] = {
    val connectPromise = Promise[Channel]()

    val connected = remote match {
      case Nil => bootstrap.clone().connect(uri.getHost, getPort(uri))
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
