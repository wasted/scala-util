package io.wasted.util.apn

import io.wasted.util._

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslHandler

import scala.concurrent.duration._
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.annotation.tailrec
import io.netty.channel.ChannelHandler.Sharable
import java.nio.ByteOrder

/**
 * Declares the different connection states
 */
object ConnectionState extends Enumeration {
  val fresh, connected, reconnecting, disconnected = Value
}

/**
 * Apple Push Notification Push class which will handle all delivery
 *
 * @param params Connection Parameters
 * @param wheelTimer WheelTimer for scheduling
 */
@Sharable
class PushService(params: Params)(implicit val wheelTimer: WheelTimer)
  extends SimpleChannelInboundHandler[ByteBuf] with Logger { thisService =>
  override val loggerName = params.name

  private final val production = new java.net.InetSocketAddress(java.net.InetAddress.getByName("gateway.push.apple.com"), 2195)
  private final val sandbox = new java.net.InetSocketAddress(java.net.InetAddress.getByName("gateway.sandbox.push.apple.com"), 2195)
  val addr: InetSocketAddress = if (params.sandbox) sandbox else production

  private val srv = new Bootstrap()
  private val bootstrap = srv.group(Netty.eventLoop)
    .channel(classOf[NioSocketChannel])
    .remoteAddress(addr)
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, params.timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast("ssl", new SslHandler(params.createSSLEngine(addr.getAddress.getHostAddress, addr.getPort)))
        p.addLast("handler", thisService)
      }
    })

  /* statistical tracking */
  private val writtenMessages = new java.util.concurrent.atomic.AtomicLong(0L)
  def sentMessages = writtenMessages.get

  private val writtenItems = new java.util.concurrent.atomic.AtomicLong(0L)
  def sentItems = writtenItems.get

  private final def sentFuture(msg: Message) = new ChannelFutureListener() {
    override def operationComplete(cf: ChannelFuture) {
      if (cf.isSuccess) {
        writtenMessages.addAndGet(1L)
        writtenItems.addAndGet(msg.items.length)
      } else queuedMessages.add(msg)
    }
  }

  /* queues for our data */
  private val queuedItems = new ConcurrentLinkedQueue[Item]()
  private val queuedMessages = new ConcurrentLinkedQueue[Message]()
  def send(item: Item) = queuedItems.add(item)
  def send(message: Message) = queuedMessages.add(message)

  /* reference to channel and state */
  private val channel = new AtomicReference[Option[Channel]](None)
  private val state = new AtomicReference[ConnectionState.Value](ConnectionState.fresh)
  def connectionState = state.get

  /**
   * Connects to the Apple Push Servers
   */
  def connect(): Unit = synchronized {
    if (state.get == ConnectionState.connected || state.get == ConnectionState.reconnecting) return
    channel.set(Tryo(bootstrap.clone.connect().sync().channel()))
    if (channel.get.isEmpty) {
      state.set(ConnectionState.disconnected)
      Schedule.once(() => connect(), 5.seconds)
    } else state.set(ConnectionState.connected)
  }

  /**
   * Disconnects from the Apple Push Server
   */
  def disconnect(): Unit = synchronized {
    if (state.get != ConnectionState.connected && state.get != ConnectionState.reconnecting) return
    channel.get.foreach(_.closeFuture())
    channel.set(None)
    state.set(ConnectionState.disconnected)
  }

  /**
   * Reconnect to Apple Push Servers
   */
  private def reconnect(): Unit = synchronized {
    if (state.get != ConnectionState.disconnected) return
    state.set(ConnectionState.reconnecting)
    disconnect()
    info("Reconnecting..")
    connect()
  }

  /**
   * Delivery messages to Apple Push Servers.
   */
  def deliver() {
    if (state.get == ConnectionState.fresh) connect()
    if (!queuedItems.isEmpty) synchronized {
      val msg = Message(queuedItems.asScala.toList)
      queuedMessages.add(msg)
      queuedItems.clear()
    }
    if (state.get == ConnectionState.connected && !queuedMessages.isEmpty) channel.get.map(write)
  }

  @tailrec
  private def write(channel: Channel): Unit = {
    val msg = queuedMessages.poll()
    info("outbound: %s", msg.bytes.array.toList)
    channel.writeAndFlush(msg.bytes.retain).addListener(sentFuture(msg))
    if (!queuedMessages.isEmpty) write(channel)
  }

  override def channelRead0(ctx: ChannelHandlerContext, buffer: ByteBuf) {
    val buf = buffer.order(ByteOrder.BIG_ENDIAN)
    val readable = buf.readableBytes
    val cmd = buf.getByte(0).toInt
    if (readable == 6 && cmd == 8) {
      val errorCode = buf.getByte(1).toInt
      val id = buf.getInt(2)
      errorCode match {
        case 0 => // "No errors encountered"
        case 1 => error("Processing error on %s", id)
        case 2 => error("Missing device token on %s", id)
        case 3 => error("Missing topic on %s", id)
        case 4 => error("Missing payload on %s", id)
        case 5 => error("Invalid token size on %s", id)
        case 6 => error("Invalid topic size on %s", id)
        case 7 => error("Invalid payload size on %s", id)
        case 8 => error("Invalid token on %s", id)
        case 10 => info("Shutdown on %s", id)
        case 255 => debug("None (unknown) on %s", id)
        case x => debug("Unknown error %s on %s", x, id)
      }
    }
  }

  override def channelActive(ctx: ChannelHandlerContext) {
    info("APN connected!")
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
    info("APN disconnected!")
    // don't override reconnecting if it's late
    state.compareAndSet(ConnectionState.connected, ConnectionState.disconnected)
    reconnect()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    http.ExceptionHandler(ctx, cause) match {
      case Some(e) => e.printStackTrace()
      case _ if connectionState != ConnectionState.disconnected => reconnect()
      case _ =>
    }
    ctx.close()
  }
}
