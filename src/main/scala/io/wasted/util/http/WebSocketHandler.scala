package io.wasted.util
package http

import com.twitter.util.Future
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.netty.util.ReferenceCountUtil

@Sharable
case class WebSocketHandler[Req <: HttpRequest](corsOrigin: String = "*",
                                                handshakerHeaders: Map[String, String] = Map.empty,
                                                connect: (Channel) => Unit = (p) => (),
                                                disconnect: (Channel) => Unit = (p) => (),
                                                httpHandler: Option[(Channel, Future[Req]) => Future[HttpResponse]] = None,
                                                handle: Option[(Channel, Future[WebSocketFrame]) => Option[Future[WebSocketFrame]]] = None)
  extends SimpleChannelInboundHandler[WebSocketFrame] { channelHandler =>

  def withHandshakerHeaders(handshakerHeaders: Map[String, String]) = copy(handshakerHeaders = handshakerHeaders)
  def onConnect(connect: (Channel) => Unit) = copy(connect = connect)
  def onDisconnect(disconnect: (Channel) => Unit) = copy(disconnect = disconnect)
  def handler(handle: (Channel, Future[WebSocketFrame]) => Option[Future[WebSocketFrame]]) = copy(handle = Some(handle))
  def withHttpHandler(httpHandler: (Channel, Future[FullHttpRequest]) => Future[HttpResponse]) = copy(httpHandler = Some(httpHandler))

  lazy val headerParser = new Headers(corsOrigin)
  lazy val wsHandshakerHeaders: HttpHeaders = if (handshakerHeaders.isEmpty) null else {
    val wshh = new DefaultHttpHeaders()
    handshakerHeaders.foreach {
      case (name, value) => wshh.add(name, value)
    }
    wshh
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    handle.map(_(ctx.channel, Future.exception(cause))) getOrElse cause.printStackTrace()
  }

  override def channelRead0(ctx: ChannelHandlerContext, msg: WebSocketFrame): Unit = {
    ReferenceCountUtil.retain(msg)
    Future {
      handle.flatMap { serverF =>
        serverF(ctx.channel(), Future.value(msg)).map { resultF =>
          resultF.map(ctx.channel().writeAndFlush).ensure(msg.release())
        }
      }.getOrElse(msg.release())
    }
  }

  def dispatch(channel: Channel, freq: Future[Req]): Future[HttpResponse] = freq.flatMap { req =>
    val headers = headerParser.get(req)
    // WebSocket Handshake needed?
    if (headers.get(HttpHeaderNames.UPGRADE).exists(_.toLowerCase == HttpHeaderValues.WEBSOCKET.toLowerCase.toString)) {
      val securityProto = headers.get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL).orNull
      val proto = if (channel.pipeline.get(HttpServer.Handlers.ssl) != null) "wss" else "ws"
      // Handshake
      val location = proto + "://" + req.headers.get(HttpHeaderNames.HOST) + "/"
      val factory = new WebSocketServerHandshakerFactory(location, securityProto, false)
      val handshaker: WebSocketServerHandshaker = factory.newHandshaker(req)
      if (handshaker == null) {
        val resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.UPGRADE_REQUIRED)
        resp.headers().set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, WebSocketVersion.V13.toHttpHeaderValue)
        Future.value(resp)
      } else {
        val promise = channel.newPromise()
        promise.addListener(handshakeCompleteListener)
        channel.pipeline().replace(HttpServer.Handlers.handler, HttpServer.Handlers.handler, channelHandler)
        handshaker.handshake(channel, req, wsHandshakerHeaders, promise)
        Future.value(null)
      }
    } else if (req.method() == HttpMethod.OPTIONS) {
      // Handles WebSocket and CORS OPTIONS requests
      val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
      headers.cors.foreach {
        case (name, value) => resp.headers().set(name, value)
      }
      Future.value(resp)
    } else httpHandler.map(_(channel, freq)) getOrElse {
      Future.value(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
    }
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
