package io.wasted.util
package http

import com.twitter.util.Future
import io.netty.channel._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._

case class WebSocketHandler(corsOrigin: String = "*",
                            handshakerHeaders: Map[String, String] = Map.empty,
                            connect: (Channel) => Unit = (p) => (),
                            disconnect: (Channel) => Unit = (p) => (),
                            handle: (Channel, Future[WebSocketFrame]) => Option[Future[WebSocketFrame]] = { (c, wsf) =>
                              wsf.map(_.release()); None
                            }) extends SimpleChannelInboundHandler[WebSocketFrame] { channelHandler =>

  def withHandshakerHeaders(handshakerHeaders: Map[String, String]) = copy(handshakerHeaders = handshakerHeaders)
  def onConnect(connect: (Channel) => Unit) = copy(connect = connect)
  def onDisconnect(disconnect: (Channel) => Unit) = copy(disconnect = disconnect)
  def handler(handle: (Channel, Future[WebSocketFrame]) => Option[Future[WebSocketFrame]]) = copy(handle = handle)

  lazy val headerParser = new Headers(corsOrigin)
  lazy val wsHandshakerHeaders: HttpHeaders = if (handshakerHeaders.isEmpty) null else {
    val wshh = new DefaultHttpHeaders()
    handshakerHeaders.foreach {
      case (name, value) => wshh.add(name, value)
    }
    wshh
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    handle(ctx.channel(), Future.exception(cause))
    cause.printStackTrace()
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: WebSocketFrame): Unit = {
    handle(ctx.channel(), Future.value(msg.retain())).map { result =>
      result.map { frame =>
        ctx.channel().writeAndFlush(frame)
      }.ensure(msg.release())
    }.getOrElse(msg.release())
  }

  def dispatch(channel: Channel, req: Future[HttpRequest]): Future[HttpResponse] = req.map { req =>
    val headers = headerParser.get(req)
    // WebSocket Handshake needed?
    if (headers.get(HttpHeaders.Names.UPGRADE).exists(_.toLowerCase == HttpHeaders.Values.WEBSOCKET.toLowerCase)) {
      val securityProto = headers.get(HttpHeaders.Names.SEC_WEBSOCKET_PROTOCOL).orNull
      val proto = if (channel.pipeline.get(HttpServer.Handlers.ssl) != null) "wss" else "ws"
      // Handshake
      val location = proto + "://" + req.headers.get(HttpHeaders.Names.HOST) + "/"
      val factory = new WebSocketServerHandshakerFactory(location, securityProto, false)
      val handshaker: WebSocketServerHandshaker = factory.newHandshaker(req)
      if (handshaker == null) {
        val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UPGRADE_REQUIRED)
        resp.headers().set(HttpHeaders.Names.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue)
        resp
      } else {
        val promise = channel.newPromise()
        promise.addListener(handshakeCompleteListener)
        channel.pipeline().replace(HttpServer.Handlers.handler, HttpServer.Handlers.handler, channelHandler)
        handshaker.handshake(channel, req, wsHandshakerHeaders, promise)
        null
      }
    } else if (req.getMethod == HttpMethod.OPTIONS) {
      // Handles WebSocket and CORS OPTIONS requests
      val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
      headers.cors.foreach {
        case (name, value) => resp.headers().set(name, value)
      }
      resp
    } else new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND)
  }

  final private val channelClosedListener = new ChannelFutureListener {
    override def operationComplete(p1: ChannelFuture): Unit = {
      disconnect(p1.channel())
    }
  }

  final private val handshakeCompleteListener = new ChannelFutureListener {
    override def operationComplete(p1: ChannelFuture): Unit = {
      connect(p1.channel())
      p1.channel().closeFuture().addListener(channelClosedListener)
    }
  }
}
