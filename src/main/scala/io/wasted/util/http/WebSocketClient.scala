package io.wasted.util
package http

import com.twitter.conversions.time._
import com.twitter.util.{ Duration, Future, Promise }
import io.netty.bootstrap._
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx._
import io.wasted.util._

/**
 * wasted.io Scala Http Client
 * @param codec Http Codec
 * @param remote Remote Host and Port
 * @param tcpConnectTimeout TCP Connect Timeout
 * @param tcpKeepAlive TCP KeepAlive
 * @param reuseAddr Reuse-Address
 * @param tcpNoDelay TCP No-Delay
 * @param soLinger soLinger
 * @param eventLoop Netty Event-Loop
 */
final case class WebSocketClient(codec: NettyHttpCodec[FullHttpRequest, HttpResponse] = NettyHttpCodec(),
                                 subprotocols: String = null,
                                 allowExtensions: Boolean = true,
                                 remote: Option[java.net.URI] = None,
                                 tcpConnectTimeout: Duration = 5.seconds,
                                 tcpKeepAlive: Boolean = false,
                                 reuseAddr: Boolean = true,
                                 tcpNoDelay: Boolean = true,
                                 soLinger: Int = 0,
                                 handle: (Channel, Future[WebSocketFrame]) => Unit = (c, f) => f.map(_.release),
                                 eventLoop: EventLoopGroup = Netty.eventLoop)(implicit wheelTimer: WheelTimer) {

  def withExtensions(allowExtensions: Boolean) = copy(allowExtensions = allowExtensions)

  def withSubprotocols(subprotocols: String) = copy(subprotocols = subprotocols)
  def withSpecifics(codec: NettyHttpCodec[FullHttpRequest, HttpResponse]) = copy(codec = codec)

  def withSoLinger(soLinger: Int) = copy(soLinger = soLinger)

  def withTcpNoDelay(tcpNoDelay: Boolean) = copy(tcpNoDelay = tcpNoDelay)

  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy(tcpKeepAlive = tcpKeepAlive)

  def withReuseAddr(reuseAddr: Boolean) = copy(reuseAddr = reuseAddr)

  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy(tcpConnectTimeout = tcpConnectTimeout)

  def withEventLoop(eventLoop: EventLoopGroup) = copy(eventLoop = eventLoop)

  def handler(handle: (Channel, Future[WebSocketFrame]) => Unit) = copy(handle = handle)

  def connectTo(uri: java.net.URI) = copy(remote = Some(uri))
  def connectTo(uri: String) = copy(remote = Some(new java.net.URI(uri)))

  private lazy val srv = new Bootstrap
  private lazy val bootstrap = srv.group(eventLoop)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, tcpNoDelay)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, tcpKeepAlive)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, reuseAddr)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, soLinger)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, tcpConnectTimeout.inMillis.toInt)

  def open(): Future[Client] = {
    assert(remote.isDefined, "Remote Host and Port are not set. Try .connectTo(host, port).")
    assert(remote.get.getPort > 0, "Port not specified!")
    val clientPromise = Promise[Client]()
    try {
      bootstrap.handler(new ChannelInitializer[SocketChannel] {
        override def initChannel(ch: SocketChannel) {
          val p = ch.pipeline()
          codec.sslCtx.foreach(e => p.addLast(HttpServer.Handlers.ssl, e.newHandler(ch.alloc())))
          val maxInitialBytes = codec.maxInitialLineLength.inBytes.toInt
          val maxHeaderBytes = codec.maxHeaderSize.inBytes.toInt
          val maxChunkSize = codec.maxChunkSize.inBytes.toInt
          p.addLast(HttpClient.Handlers.codec, new HttpClientCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize))
          if (codec.chunking && !codec.chunked) {
            p.addLast(HttpClient.Handlers.aggregator, new HttpObjectAggregator(codec.maxChunkSize.inBytes.toInt))
          }
          if (codec.decompression) {
            p.addLast(HttpClient.Handlers.decompressor, new HttpContentDecompressor())
          }
          p.addLast(new SimpleChannelInboundHandler[FullHttpResponse] {
            private val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
              remote.get, WebSocketVersion.V13, subprotocols, true, new DefaultHttpHeaders())

            override def channelActive(ctx: ChannelHandlerContext) {
              val promise = ctx.newPromise()
              handshaker.handshake(ctx.channel, promise)
              promise.addListener(new ChannelFutureListener {
                override def operationComplete(f: ChannelFuture): Unit = {
                  // don't overflow the server immediately after handshake
                  Schedule(() => clientPromise.setValue(Client(ctx.channel)), 100.millis)
                }
              })
            }

            override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
              if (!handshaker.isHandshakeComplete) {
                handshaker.finishHandshake(ctx.channel(), msg.retain())
                ctx.channel().pipeline().remove(this)
              } else ctx.channel().close() // this should not happen
            }

            override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
              handle(ctx.channel(), Future.exception(cause))
              ctx.close()
            }
          })

          p.addLast(HttpClient.Handlers.handler, new SimpleChannelInboundHandler[WebSocketFrame] {
            override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
              handle(ctx.channel(), Future.exception(cause))
            }

            override def channelRead0(ctx: ChannelHandlerContext, msg: WebSocketFrame): Unit = {
              handle(ctx.channel(), Future.value(msg))
            }
          })
        }
      }).connect(remote.get.getHost, remote.get.getPort).sync()
    } catch {
      case t: Throwable => clientPromise.setException(t)
    }
    clientPromise
  }

  case class Client(channel: Channel) {
    val onDisconnect = Promise[Unit]()
    channel.closeFuture().addListener(new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = onDisconnect.setDone()
    })
    def disconnect(close: ChannelFuture => Unit = (c) => ()): Unit = {
      channel.closeFuture().addListener(new ChannelFutureListener {
        override def operationComplete(f: ChannelFuture): Unit = {
          close(f)
        }
      })
    }

    def !(wsf: WebSocketFrame): Unit = channel.writeAndFlush(wsf)
    def write(wsf: WebSocketFrame): Unit = channel.write(wsf)
    def flush(): Unit = channel.flush()
  }

}