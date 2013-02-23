package io.wasted.util.http

import io.wasted.util._

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout._
import io.netty.util.CharsetUtil

import java.net.URI
import java.util.UUID
import java.util.concurrent.Executor
import javax.net.ssl.SSLEngine
import java.net.InetSocketAddress
import scala.util.{ Try, Success, Failure }

/**
 * Thruput WebSocket Client class which will handle all delivery.
 *
 * @param uri Endpoint URI for the Thruput Client
 * @param auth Authentication Key for the Thruput platform
 * @param sign Signing Key for the Thruput platform
 * @param callback Callback for every non-connection WebSocketFrame (Binary and Text)
 * @param timeout Connect timeout in seconds
 * @param engine Optional SSLEngine
 */
class Thruput(
  uri: URI,
  auth: UUID,
  sign: UUID,
  from: Option[String] = None,
  val callback: (ByteBufHolder) => Any = (x) => x.release,
  timeout: Int = 5,
  engine: Option[SSLEngine] = None) extends Wactor(100000) {
  TP =>

  override val loggerName = uri.getHost + ":" + auth.toString
  private def session = UUID.randomUUID
  private var channel: Option[Channel] = None
  private[http] var handshakeFuture: ChannelPromise = null.asInstanceOf[ChannelPromise]
  private var disconnected = false
  private var connecting = false
  private var reconnecting = false

  private val writeCount = new java.util.concurrent.atomic.AtomicLong(0L)

  private val srv = new Bootstrap()
  private val bootstrap = srv.group(new NioEventLoopGroup)
    .channel(classOf[NioSocketChannel])
    .remoteAddress(new InetSocketAddress(uri.getHost, uri.getPort))
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast("decoder", new HttpResponseDecoder)
        p.addLast("encoder", new HttpRequestEncoder)
        p.addLast("aggregator", new HttpObjectAggregator(8192))
        p.addLast("ws-handler", new ThruputResponseAdapter(uri, TP))
      }
    })

  private sealed trait Action { def run(): Unit }

  private final case object Connect extends Action {
    def run() {
      connecting = true
      channel match {
        case Some(ch) =>
          debug("Connection already established")
        case _ =>
          Try {
            writeCount.set(0L)
            val ch = bootstrap.clone.connect().sync().channel()
            handshakeFuture.sync()

            val body = from match {
              case Some(from) => """{"from":"%s","thruput":true}""".format(from)
              case None => """{"thruput":true}"""
            }
            ch.write(new TextWebSocketFrame("""{"auth":"%s","sign":"%s","body":%s,"session":"%s"}""".format(
              auth.toString, io.wasted.util.Hashing.sign(sign.toString, body), body, session.toString))).sync()
            ch
          } match {
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

  private final case object Disconnect extends Action {
    def run() {
      disconnected = true
      channel match {
        case Some(ch) =>
          ch.flush
          ch.write(new CloseWebSocketFrame())

          // WebSocketClientHandler will close the connection when the server
          // responds to the CloseWebSocketFrame.
          ch.closeFuture().sync()
        case _ => debug("Connection not established")
      }
      channel = None
    }
  }

  private final case object Reconnect extends Action {
    def run() {
      reconnecting = true
      Disconnect.run()
      Connect.run()
      reconnecting = false
    }
  }

  def receive = {
    case action: Action => action.run()
    case msg: String =>
      if (reconnecting || disconnected || connecting) TP ! msg
      else channel match {
        case Some(ch) =>
          Try(ch.write(new TextWebSocketFrame(msg))) match {
            case Success(f) =>
              if (writeCount.addAndGet(1L) % 10 == 0) ch.flush()
            case Failure(e) =>
              TP ! msg
          }
        case _ =>
          connect()
          TP ! msg
      }
  }

  /**
   * Reconnect this Thruput Client Socket. (only used by Handler)
   */
  def reconnect() {
    if (reconnecting || disconnected || connecting) return
    TP !! Reconnect
  }

  /**
   * Connect this Thruput Client Socket.
   */
  def connect() {
    if (channel != None) return
    TP !! Connect
  }

  /**
   * Disconnect this Thruput Client Socket.
   */
  def disconnect() {
    TP !! Disconnect
  }

  def write(string: String) {
    TP ! string
  }

  /**
   * Shutdown this client.
   */
  def shutdown() {
    disconnected = true
    srv.shutdown()
    on.set(0)
  }
}

/**
 * Empty Netty Response Adapter which is used for Thruput high-performance delivery.
 */
@ChannelHandler.Sharable
class ThruputResponseAdapter(uri: URI, client: Thruput) extends ChannelInboundMessageHandlerAdapter[Object] with Logger {
  private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders())

  override def beforeAdd(ctx: ChannelHandlerContext) {
    client.handshakeFuture = ctx.newPromise()
  }

  override def channelActive(ctx: ChannelHandlerContext) {
    handshaker.handshake(ctx.channel)
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
    info("WebSocket Client disconnected!")
    client.reconnect
  }

  override def messageReceived(ctx: ChannelHandlerContext, msg: Object) {
    val ch = ctx.channel()

    msg match {
      case response: FullHttpResponse if !handshaker.isHandshakeComplete =>
        handshaker.finishHandshake(ch, response)
        info("WebSocket Client connected!")
        client.handshakeFuture.setSuccess()
      case response: FullHttpResponse =>
        throw new Exception("Unexpected FullHttpResponse (status=" + response.getStatus() + ", content=" + response.data().toString(CharsetUtil.UTF_8) + ")")
      case frame: BinaryWebSocketFrame =>
        debug("WebSocket BinaryFrame received message")
        client.callback(frame.retain)
      case frame: TextWebSocketFrame =>
        debug("WebSocket TextFrame received message: " + frame.text())
        client.callback(frame.retain)
      case frame: PongWebSocketFrame =>
        debug("WebSocket Client received pong")
      case frame: CloseWebSocketFrame =>
        debug("WebSocket Client received closing")
        ch.close()
      case o: Object =>
        error("Unsupported response type! " + o.toString)
        ch.close()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    if (!client.handshakeFuture.isDone()) client.handshakeFuture.setFailure(cause)
    ExceptionHandler(ctx, cause) match {
      case Some(e) => e.printStackTrace
      case _ => client.reconnect
    }
    ctx.close()
  }
}
