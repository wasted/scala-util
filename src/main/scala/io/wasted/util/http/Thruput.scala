package io.wasted.util.http

import io.wasted.util._

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket._
import io.netty.channel.socket.nio._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout._
import io.netty.util.CharsetUtil

import javax.net.ssl.SSLEngine
import java.net.InetSocketAddress
import scala.util.{ Try, Success, Failure }

/**
 * Thruput WebSocket Client class which will handle all delivery.
 */
class Thruput(from: String, auth: java.util.UUID, sign: java.util.UUID, uri: java.net.URI, timeout: Int = 5, engine: Option[SSLEngine] = None) extends Logger {

  private def session = java.util.UUID.randomUUID
  private var channel: Option[Channel] = None
  private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders())
  private val adapter = new ThruputResponseAdapter(handshaker, this)
  private var disconnecting = false
  private var reconnecting = false

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
        p.addLast("ws-handler", adapter)
      }
    })

  def reconnect(): Unit = synchronized {
    if (!reconnecting) {
      reconnecting = true
      disconnect()
      connect()
      reconnecting = false
    }
  }

  def connect() {
    if (disconnecting) return

    channel match {
      case Some(ch) => debug("Connection already established")
      case _ =>
        val ch = bootstrap.duplicate.connect().sync().channel()
        adapter.handshakeFuture().sync()

        val body = """{"from":"%s","thruput":true}""".format(from)
        ch.write(new TextWebSocketFrame("""{"auth":"%s","sign":"%s","body":%s,"session":"%s"}""".format(
          auth.toString, io.wasted.util.Hashing.sign(sign.toString, body), body, session.toString))).sync()

        channel = Some(ch)
    }
  }

  def disconnect() {
    disconnecting = true
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
    disconnecting = false
  }

  def write(wsf: WebSocketFrame) {
    channel match {
      case Some(ch) =>
        ch.write(wsf)
      case _ => debug("Connection not established")
    }
  }

  def shutdown() {
    srv.shutdown()
  }

}

/**
 * Empty Netty Response Adapter which is used for Thruput high-performance delivery.
 */
@ChannelHandler.Sharable
class ThruputResponseAdapter(handshaker: WebSocketClientHandshaker, client: Thruput) extends ChannelInboundMessageHandlerAdapter[Object] with Logger {
  private var _handshakeFuture: ChannelPromise = null.asInstanceOf[ChannelPromise]
  def handshakeFuture() = _handshakeFuture

  override def beforeAdd(ctx: ChannelHandlerContext) {
    _handshakeFuture = ctx.newPromise()
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
        handshakeFuture.setSuccess()
      case response: FullHttpResponse =>
        throw new Exception("Unexpected FullHttpResponse (status=" + response.status() + ", content=" + response.data().toString(CharsetUtil.UTF_8) + ")")
      case frame: TextWebSocketFrame =>
        debug("WebSocket Client received message: " + frame.text())
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
    cause.printStackTrace //ExceptionHandler(ctx, cause)
    if (!handshakeFuture.isDone()) handshakeFuture.setFailure(cause)
    ctx.close()
    client.reconnect
  }
}
