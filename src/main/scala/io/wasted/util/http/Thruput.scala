package io.wasted.util.http

import java.net.{InetSocketAddress, URI}
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLEngine

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.ssl.SslHandler
import io.netty.util.CharsetUtil
import io.wasted.util._

import scala.util.{Failure, Success, Try}

object Thruput {
  object State extends Enumeration {
    val Connecting, Connected, Reconnecting, Disconnected = Value
  }
}

/**
 * Thruput WebSocket Client class which will handle all delivery.
 *
 * @param uri Endpoint URI for the Thruput Client
 * @param auth Authentication Key for the Thruput platform
 * @param sign Signing Key for the Thruput platform
 * @param from Username to use (optional)
 * @param room Room to use (optional)
 * @param callback Callback for every non-connection WebSocketFrame (Binary and Text)
 * @param states Callback for connection state changes
 * @param timeout Connect timeout in seconds
 * @param engine Optional SSLEngine
 */
class Thruput(
  uri: URI,
  auth: UUID,
  sign: UUID,
  from: Option[String] = None,
  room: Option[String] = None,
  val callback: (ByteBufHolder) => Any = (x) => x.release,
  states: (Thruput.State.Value) => Any = (x) => x,
  timeout: Int = 5,
  engine: Option[SSLEngine] = None) extends Wactor(100000) {
  TP =>
  override val loggerName = getClass.getCanonicalName + ":" + uri.toString + auth
  private def session = UUID.randomUUID
  private var channel: Option[Channel] = None
  private[http] var handshakeFuture: ChannelPromise = _
  private var disconnected = false
  private var connecting = false
  private var reconnecting = false
  private val _state = new AtomicReference[Thruput.State.Value](Thruput.State.Disconnected)
  def state = _state.get()

  private def setState(state: Thruput.State.Value): Unit = {
    this._state.set(state)
    states(state)
  }

  private val bootstrap = new Bootstrap().group(Netty.eventLoop)
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
        engine.map(e => p.addLast("ssl", new SslHandler(e)))
        p.addLast("decoder", new HttpResponseDecoder)
        p.addLast("encoder", new HttpRequestEncoder)
        p.addLast("aggregator", new HttpObjectAggregator(8192))
        p.addLast("ws-handler", new ThruputResponseAdapter(uri, TP))
      }
    })

  // This method is used to send WebSocketFrames async
  private def writeToChannel(channel: Channel, wsf: WebSocketFrame) {
    wsf.retain
    wsf match {
      case a: TextWebSocketFrame => debug("Sending: " + a.text())
      case other => debug("Sending: " + other.getClass.getSimpleName)
    }
    val eventLoop = channel.eventLoop()
    eventLoop.inEventLoop match {
      case true => channel.writeAndFlush(wsf)
      case false =>
        eventLoop.execute(new Runnable() {
          override def run(): Unit = channel.writeAndFlush(wsf)
        })
    }
  }

  private sealed trait Action { def run(): Unit }

  private case object Connect extends Action {
    def run() {
      connecting = true
      channel match {
        case Some(ch) =>
          debug("Connection already established")
        case _ =>
          Try {
            val ch = bootstrap.clone.connect().sync().channel()
            handshakeFuture.sync()

            val body = (room, from) match {
              case (Some(uroom), Some(ufrom)) => """{"room":"%s","thruput":true,"from":"%s"}""".format(uroom, ufrom)
              case (_, Some(ufrom)) => """{"from":"%s","thruput":true}""".format(ufrom)
              case _ => """{"thruput":true}"""
            }
            writeToChannel(ch, new TextWebSocketFrame("""{"auth":"%s","sign":"%s","body":%s,"session":"%s"}""".format(
              auth.toString, io.wasted.util.Hashing.sign(sign.toString, body), body, session.toString)))
            ch
          } match {
            case Success(ch) =>
              channel = Some(ch)
              setState(Thruput.State.Connected)
            case Failure(e) =>
              connect()
              setState(Thruput.State.Connecting)
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
        case Some(ch) =>
          writeToChannel(ch, new CloseWebSocketFrame())

          // WebSocketClientHandler will close the connection when the server
          // responds to the CloseWebSocketFrame.
          ch.closeFuture().sync()
          setState(Thruput.State.Disconnected)
        case _ => debug("Connection not established")
      }
      channel = None
    }
  }

  private case object Reconnect extends Action {
    def run() {
      reconnecting = true
      setState(Thruput.State.Reconnecting)
      Disconnect.run()
      Connect.run()
      reconnecting = false
    }
  }

  def receive = {
    case action: Action => action.run()
    case msg: WebSocketFrame =>
      if (reconnecting || disconnected || connecting) TP ! msg
      else channel match {
        case Some(ch) =>
          Try(writeToChannel(ch, msg)) match {
            case Success(f) =>
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
    if (channel.isDefined) return
    TP !! Connect
  }

  /**
   * Disconnect this Thruput Client Socket.
   */
  def disconnect() {
    TP !! Disconnect
  }

  def write(wsf: WebSocketFrame) {
    TP ! wsf
  }

  def write(string: String) {
    TP ! new TextWebSocketFrame(string)
  }

  /**
   * Shutdown this client.
   */
  def shutdown() {
    disconnect()
    disconnected = true
    on.set(0)
  }
}

/**
 * Empty Netty Response Adapter which is used for Thruput high-performance delivery.
 */
class ThruputResponseAdapter(uri: URI, client: Thruput) extends SimpleChannelInboundHandler[Object] {
  private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders())

  override def handlerAdded(ctx: ChannelHandlerContext) {
    client.handshakeFuture = ctx.newPromise()
  }

  override def channelActive(ctx: ChannelHandlerContext) {
    handshaker.handshake(ctx.channel)
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
    client.info("WebSocket Client disconnected!")
    client.reconnect()
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: Object) {
    val ch = ctx.channel()

    msg match {
      case response: FullHttpResponse if !handshaker.isHandshakeComplete =>
        handshaker.finishHandshake(ch, response)
        client.info("WebSocket Client connected!")
        client.handshakeFuture.setSuccess()
      case response: FullHttpResponse =>
        throw new Exception("Unexpected FullHttpResponse (status=" + response.status() + ", content=" + response.content().toString(CharsetUtil.UTF_8) + ")")
      case frame: BinaryWebSocketFrame =>
        client.debug("WebSocket BinaryFrame received message")
        client.callback(frame.retain)
      case frame: TextWebSocketFrame =>
        client.debug("WebSocket TextFrame received message: " + frame.text())
        client.callback(frame.retain)
      case frame: PongWebSocketFrame =>
        client.debug("WebSocket Client received pong")
      case frame: CloseWebSocketFrame =>
        client.debug("WebSocket Client received closing")
        ch.close()
      case o: Object =>
        client.error("Unsupported response type! " + o.toString)
        ch.close()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    if (!client.handshakeFuture.isDone) client.handshakeFuture.setFailure(cause)
    ExceptionHandler(ctx, cause) match {
      case Some(e) =>
      case _ => client.reconnect()
    }
    ctx.close()
  }
}
