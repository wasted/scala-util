package io.wasted.util.apn

import io.wasted.util._

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.handler.ssl.SslHandler

import java.security.KeyStore
import javax.net.ssl.{ KeyManagerFactory, SSLContext }
import scala.util.{ Try, Success, Failure }

/**
 * Apple Push Notification Client message.
 *
 * @param deviceToken Apple Device Token as Hex-String
 * @param payload APN Payload
 * @param ident Transaction Identifier
 * @param expire Expiry
 */
case class APNMessage(deviceToken: String, payload: String, ident: Option[Int] = None, expire: Option[Int] = None) {
  lazy val bytes = {
    val payloadA: Array[Byte] = payload.toArray.map(_.toByte)
    val deviceTokenA: Array[Byte] = deviceToken.grouped(2).map(Integer.valueOf(_, 16).toByte).toArray

    // check if we have to use enhanced command set
    (ident, expire) match {
      case (None, None) =>
        val buf = Unpooled.buffer(1 + 2 + deviceTokenA.length + 2 + payload.length)
        buf.writeByte(0.toByte & 0xff) // Command set version 1
        buf.writeShort(deviceTokenA.length)
        buf.writeBytes(deviceTokenA)
        buf.writeShort(payloadA.length)
        buf.writeBytes(payloadA)
        buf

      case _ =>
        val buf = Unpooled.buffer(1 + 4 + 4 + 2 + deviceTokenA.length + 2 + payload.length)
        buf.writeByte(1.toByte & 0xff) // Command set version 1
        buf.writeInt(ident.getOrElse(0) & 0xffff) // Transaction Identifier
        buf.writeInt(expire.getOrElse(0) & 0xffff) // Message Expiry
        buf.writeShort(deviceTokenA.length)
        buf.writeBytes(deviceTokenA)
        buf.writeShort(payloadA.length)
        buf.writeBytes(payloadA)
        buf
    }
  }
  lazy val size = bytes.capacity
}

/**
 * Apple Push Notification Client companion object.
 */
object APN {
  final val productionHost = new java.net.InetSocketAddress(java.net.InetAddress.getByName("gateway.push.apple.com"), 2195)
  final val sandboxHost = new java.net.InetSocketAddress(java.net.InetAddress.getByName("gateway.sandbox.push.apple.com"), 2195)
}

/**
 * Apple Push Notification Client class which will handle all delivery.
 *
 * @param loggerName Name of this APN instance in Log-lines
 * @param p12 exported .p12 from KeyChain
 * @param secret Password to open the p12 file
 * @param sandbox Use sandbox or producation servers
 * @param timeout Connect timeout to servers
 */
class APN(override val loggerName: String, p12: java.io.InputStream, secret: String, sandbox: Boolean = false, timeout: Int = 5) extends Wactor(100000) {
  thisAPN =>

  private var channel: Option[Channel] = None
  private[apn] var disconnected = false
  private[apn] var connecting = false
  private[apn] var reconnecting = false

  private val writeCount = new java.util.concurrent.atomic.AtomicLong(0L)
  private val srv = new Bootstrap()
  private val bootstrap = srv.group(Netty.eventLoop)
    .channel(classOf[NioSocketChannel])
    .remoteAddress(if (sandbox) APN.sandboxHost else APN.productionHost)
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast("ssl", new SslHandler(sslManager.self))
        p.addLast("handler", new APNResponseAdapter(thisAPN))
      }
    })

  private val sslManager = ssl.Ssl.server(p12, secret, ssl.KeyStoreType.P12)

  private sealed trait Action { def run(): Unit }

  private case object Connect extends Action {
    def run() {
      connecting = true
      channel match {
        case Some(ch) =>
          debug("Connection already established")
        case _ =>
          writeCount.set(0L)
          Try[Channel](bootstrap.clone.connect().sync().channel()) match {
            case Success(ch) =>
              channel = Some(ch)
            case Failure(e) =>
              warn("Error while connecting: " + e.toString, e)
              connect()
          }
      }
      connecting = false
      disconnected = false
    }
  }

  private case object Disconnect extends Action {
    def run() {
      disconnected = true
      channel match {
        case Some(ch) => ch.close
        case _ => debug("Connection not established")
      }
      channel = None
    }
  }

  private case object Reconnect extends Action {
    def run() {
      reconnecting = true
      Disconnect.run()
      info("Reconnecting..")
      Connect.run()
      reconnecting = false
    }
  }

  def receive = {
    case action: Action => action.run()
    case msg: APNMessage if msg.size > 255 =>
      warn("Payload was too long: " + msg)
    case msg: APNMessage =>
      if (reconnecting || disconnected || connecting) this ! msg
      else channel match {
        case Some(ch) =>
          ch.write(msg.bytes).addListener(new ChannelFutureListener() {
            override def operationComplete(cf: ChannelFuture) {
              if (cf.isSuccess) writeCount.addAndGet(1L)
              else thisAPN ! msg
            }
          })
          ch.flush()
        case _ =>
          connect()
          this ! msg
      }
  }

  /**
   * Reconnect this APN Client Socket. (only used by Handler)
   */
  def reconnect() {
    if (reconnecting || disconnected || connecting) return
    this !! Reconnect
  }

  /**
   * Connect this APN Client Socket.
   */
  def connect() {
    if (channel != None) return
    this !! Connect
  }

  /**
   * Disconnect this APN Client Socket.
   */
  def disconnect() {
    this !! Disconnect
  }

  def write(msg: APNMessage) {
    this ! msg
  }

  /**
   * Shutdown this client.
   */
  def shutdown() {
    disconnected = true
    on.set(0)
  }
}

/**
 * Empty Netty Response Adapter which is used for APN high-performance delivery.
 */
@ChannelHandler.Sharable
class APNResponseAdapter(client: APN) extends SimpleChannelInboundHandler[ByteBuf] with Logger {
  override val loggerName = client.loggerName

  override def channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
    val ch = ctx.channel()
    buf.release
    // TODO we should do some error codes (maybe), but who would actually collect them?
    //ch.close()
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
    info("APN disconnected!")
    client.reconnect()
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    http.ExceptionHandler(ctx, cause) match {
      case Some(e) => e.printStackTrace()
      case _ if !client.disconnected => client.reconnect()
      case _ =>
    }
    ctx.close()
  }
}
