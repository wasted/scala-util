package io.wasted.util.apn

import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.wasted.util._

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * Declares the different connection states
 */
object ConnectionState extends Enumeration {
  val fresh, connected, disconnected = Value
}

/**
 * Apple Push Notification Push class which will handle all delivery
 *
 * @param params Connection Parameters
 * @param eventLoop Optional custom event loop for proxy applications
 * @param wheelTimer WheelTimer for scheduling
 */
@Sharable
class PushService(params: Params, eventLoop: EventLoopGroup = Netty.eventLoop)(implicit val wheelTimer: WheelTimer)
  extends SimpleChannelInboundHandler[ByteBuf] with Logger { thisService =>
  override val loggerName = getClass.getCanonicalName + ":" + params.name
  def addr: InetSocketAddress = if (params.sandbox) sandbox else production

  private final val production = {
    new java.net.InetSocketAddress(java.net.InetAddress.getByName("gateway.push.apple.com"), 2195)
  }
  private final val sandbox = {
    new java.net.InetSocketAddress(java.net.InetAddress.getByName("gateway.sandbox.push.apple.com"), 2195)
  }

  private val srv = new Bootstrap()
  private val bootstrap = srv.group(eventLoop)
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
        Tryo(params.sslCtx.newHandler(ch.alloc())) match {
          case Some(handler) => p.addLast("ssl", handler)
          case _ =>
            error("Unable to create SSL Context")
            ch.close()
        }
      }
    })

  /* separate state for flushing */
  private val flushing = new java.util.concurrent.atomic.AtomicBoolean(false)

  /* statistical tracking */
  private val written = new java.util.concurrent.atomic.AtomicLong(0L)
  def sentMessages = written.get

  private final def sentFuture(msg: Message) = new ChannelFutureListener() {
    override def operationComplete(cf: ChannelFuture) {
      if (cf.isSuccess) written.addAndGet(1L)
      else queued.add(msg)
    }
  }

  /* queues for our data */
  private val queued = new ConcurrentLinkedQueue[Message]()
  def send(message: Message): Boolean = channel.get match {
    case Some(chan) if state.get == ConnectionState.connected =>
      chan.writeAndFlush(message.bytes.retain).addListener(sentFuture(message))
      if (!queued.isEmpty && !flushing.get) deliverQueued()
      true
    case _ => queued.add(message)
  }

  /* reference to channel and state */
  private val ping = new AtomicReference[Option[Schedule.Action]](None)
  private val channel = new AtomicReference[Option[Channel]](None)
  private val state = new AtomicReference[ConnectionState.Value](ConnectionState.fresh)
  def connectionState = state.get

  /**
   * Connects to the Apple Push Servers
   */
  def connect(): Unit = synchronized {
    if (state.get == ConnectionState.connected) return
    channel.set(Tryo(bootstrap.clone.connect().sync().channel()))
    if (channel.get.isEmpty) {
      state.set(ConnectionState.disconnected)
      Schedule.once(() => connect(), 5.seconds)
    } else {
      state.set(ConnectionState.connected)
      ping.set(Some(Schedule.again(() => channel.get.foreach { case chan if !chan.isActive => reconnect() case _ => }, 5.seconds, 5.seconds)))
    }
  }

  /**
   * Disconnects from the Apple Push Server
   */
  def disconnect(): Unit = synchronized {
    channel.get.foreach(_.close())
    channel.set(None)
    ping.get.foreach(_.cancel())
    ping.set(None)
    state.set(ConnectionState.disconnected)
  }

  /**
   * Reconnect to Apple Push Servers
   */
  private def reconnect(): Unit = synchronized {
    disconnect()
    info("Reconnecting..")
    connect()
  }

  /**
   * Delivery messages to Apple Push Servers.
   */
  private def deliverQueued(): Unit = Future {
    if (!flushing.compareAndSet(false, true) && state.get == ConnectionState.connected && !queued.isEmpty) {
      channel.get.foreach(write)
      flushing.set(false)
    }
  }

  @tailrec
  private def write(channel: Channel): Unit = {
    val msg = queued.poll()
    channel.writeAndFlush(msg.bytes.retain).addListener(sentFuture(msg))
    if (!queued.isEmpty) write(channel)
  }

  override def channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
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
    reconnect()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    http.ExceptionHandler(ctx, cause) match {
      case Some(e) => e.printStackTrace()
      case _ =>
    }
    ctx.close()
  }
}
