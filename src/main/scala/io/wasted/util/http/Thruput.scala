package io.wasted.util.http

import io.wasted.util.Logger

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

/**
 * Thruput WebSocket Client class which will handle all delivery.
 */
class Thruput(from: String, auth: java.util.UUID, sign: java.util.UUID, uri: java.net.URI, timeout: Int = 5, engine: Option[SSLEngine] = None) extends Logger {

  private val session = java.util.UUID.randomUUID
  private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false, new DefaultHttpHeaders())
  private val adapter = new ThruputResponseAdapter(handshaker)

  lazy val srv = new Bootstrap
  lazy val bootstrap = srv.group(new NioEventLoopGroup)
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

  def connect(): Channel = {
    val ch = bootstrap.connect().sync().channel()
    adapter.handshakeFuture().sync()

    val body = """{"from":"%s","thruput":true}""".format(from)
    ch.write(new TextWebSocketFrame("""{"auth":"%s","sign":"%s","body":%s,"session":"%s"}""".format(
      auth.toString, io.wasted.util.Hashing.sign(sign.toString, body), body, session.toString))).sync()
    ch
  }

  def disconnect(ch: Channel) {
    ch.write(new CloseWebSocketFrame())

    // WebSocketClientHandler will close the connection when the server
    // responds to the CloseWebSocketFrame.
    ch.closeFuture().sync()
  }

  def close() {
    srv.shutdown()
  }
}

/**
 * Empty Netty Response Adapter which is used for Thruput high-performance delivery.
 */
class ThruputResponseAdapter(handshaker: WebSocketClientHandshaker) extends ChannelInboundMessageHandlerAdapter[Object] with Logger {
  private var _handshakeFuture: ChannelPromise = null.asInstanceOf[ChannelPromise]
  def handshakeFuture() = _handshakeFuture

  override def beforeAdd(ctx: ChannelHandlerContext) {
    _handshakeFuture = ctx.newPromise()
  }

  override def channelActive(ctx: ChannelHandlerContext) {
    handshaker.handshake(ctx.channel)
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
    warn("WebSocket Client disconnected!")
  }

  override def messageReceived(ctx: ChannelHandlerContext, msg: Object) {
    val ch = ctx.channel()
    if (!handshaker.isHandshakeComplete()) {
      handshaker.finishHandshake(ch, msg.asInstanceOf[FullHttpResponse])
      warn("WebSocket Client connected!")
      handshakeFuture.setSuccess()
      return
    }

    msg match {
      case response: FullHttpResponse =>
        throw new Exception("Unexpected FullHttpResponse (status=" + response.status() +
          ", content=" + response.data().toString(CharsetUtil.UTF_8) + ')');
      case frame: TextWebSocketFrame =>
        warn("WebSocket Client received message: " + frame.text())
      case frame: PongWebSocketFrame =>
        warn("WebSocket Client received pong")
      case frame: CloseWebSocketFrame =>
        warn("WebSocket Client received closing")
        ch.close()
      case _ =>
        error("Unsupported response type!")
        ch.close()
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace //ExceptionHandler(ctx, cause)
    if (!handshakeFuture.isDone()) handshakeFuture.setFailure(cause)
    ctx.close()
  }
}
