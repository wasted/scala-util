package io.wasted.util.http

import java.net.InetSocketAddress

import com.twitter.conversions.time._
import com.twitter.util._
import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http._
import io.netty.handler.ssl.SslHandler
import io.wasted.util._

object HttpServer {
  private[http] val defaultHandler: (Channel, Future[HttpMessage]) => Future[HttpResponse] = { (ch, msg) =>
    Future.value(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
  }

  val closeListener = new ChannelFutureListener {
    override def operationComplete(f: ChannelFuture): Unit = {
      f.channel().close()
    }
  }

  /**
   * These can be used to modify the pipeline afterwards without having to guess their names
   */
  object Handlers {
    val ssl = "ssl"
    val codec = "codec"
    val dechunker = "dechunker"
    val compressor = "compressor"
    val timeout = "timeout"
    val handler = "handler"
  }
}

/**
 * wasted.io Scala Http Server
 * @param codec Http Codec
 * @param httpValidateHeaders Validate HTTP Headers
 * @param tcpConnectTimeout TCP Connect Timeout
 * @param tcpKeepAlive TCP KeepAlive
 * @param reuseAddr Reuse-Address
 * @param tcpNoDelay TCP No-Delay
 * @param soLinger soLinger
 * @param sendAllocator ByteBuf send Allocator
 * @param recvAllocator ByteBuf recv Allocator
 * @param parentLoop Netty Event-Loop for Parents
 * @param childLoop Netty Event-Loop for Children
 * @param pipeline Setup extra handlers on the Netty Pipeline
 * @param handle Service to handle HttpRequests
 */
final case class HttpServer[Req <: HttpMessage](codec: HttpCodec[Req] = HttpCodec[Req](),
                                                httpValidateHeaders: Boolean = true,
                                                tcpConnectTimeout: Duration = 5.seconds,
                                                tcpKeepAlive: Boolean = false,
                                                reuseAddr: Boolean = true,
                                                tcpNoDelay: Boolean = true,
                                                soLinger: Int = 0,
                                                sendAllocator: ByteBufAllocator = UnpooledByteBufAllocator.DEFAULT,
                                                recvAllocator: RecvByteBufAllocator = AdaptiveRecvByteBufAllocator.DEFAULT,
                                                parentLoop: EventLoopGroup = Netty.eventLoop,
                                                childLoop: EventLoopGroup = Netty.eventLoop,
                                                pipeline: (ChannelPipeline) => Unit = p => (),
                                                handle: (Channel, Future[Req]) => Future[HttpResponse] = HttpServer.defaultHandler)
  extends Logger {

  def withSpecifics(codec: HttpCodec[Req]) = copy[Req](codec = codec)
  def withSoLinger(soLinger: Int) = copy[Req](soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy[Req](tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy[Req](tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy[Req](reuseAddr = reuseAddr)
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy[Req](tcpConnectTimeout = tcpConnectTimeout)
  def withPipeline(pipeline: (ChannelPipeline) => Unit) = copy[Req](pipeline = pipeline)
  def handler(handle: (Channel, Future[Req]) => Future[HttpResponse]) = copy[Req](handle = handle)

  def withEventLoop(eventLoop: EventLoopGroup) = copy[Req](parentLoop = eventLoop, childLoop = eventLoop)
  def withEventLoop(parentLoop: EventLoopGroup, childLoop: EventLoopGroup) = copy[Req](parentLoop = parentLoop, childLoop = childLoop)
  def withChildLoop(eventLoop: EventLoopGroup) = copy[Req](childLoop = eventLoop)
  def withParentLoop(eventLoop: EventLoopGroup) = copy[Req](parentLoop = eventLoop)

  private[this] lazy val srv = new ServerBootstrap
  private[this] lazy val chan = {
    val pipelineFactory = new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        codec.engine.foreach(e => p.addLast(HttpServer.Handlers.ssl, new SslHandler(e().self)))
        val maxInitialBytes = codec.maxInitialLineLength.inBytes.toInt
        val maxHeaderBytes = codec.maxHeaderSize.inBytes.toInt
        val maxChunkSize = codec.maxChunkSize.inBytes.toInt
        p.addLast(HttpServer.Handlers.codec, new HttpServerCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize))
        if (codec.compressionLevel > 0) {
          p.addLast(HttpServer.Handlers.compressor, new HttpContentCompressor(codec.compressionLevel))
        }
        if (codec.chunking && !codec.chunked) {
          p.addLast(HttpServer.Handlers.dechunker, new HttpObjectAggregator(maxChunkSize))
        }

        p.addLast(HttpServer.Handlers.handler, new SimpleChannelInboundHandler[Req] {
          override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            error(cause.getMessage)
            debug(cause)
            handle(ctx.channel(), Future.exception(cause))
          }

          def channelRead0(ctx: ChannelHandlerContext, req: Req) {
            req match {
              case msg: ByteBufHolder => msg.content.retain()
              case _ =>
            }
            handle(ctx.channel(), Future.value(req)).rescue {
              case t =>
                error(t.getMessage)
                debug(t)
                val resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR)
                Future.value(resp)
            }.map {
              case resp: HttpResponse =>
                val ka = {
                  if (HttpHeaders.isKeepAlive(resp)) HttpHeaders.Values.KEEP_ALIVE
                  else HttpHeaders.Values.CLOSE
                }
                resp.headers().set(HttpHeaders.Names.CONNECTION, ka)
                val written = ctx.channel().writeAndFlush(resp)
                if (!HttpHeaders.isKeepAlive(req)) written.addListener(HttpServer.closeListener)
              case resp => ctx.channel().writeAndFlush(resp)
            } ensure (req match {
              case holder: ByteBufHolder => holder.release()
            })
          }
        })

        // setup custom pipeline things
        pipeline(p)
      }
    }
    val bs = srv.group(parentLoop, childLoop)
      .channel(classOf[NioServerSocketChannel])
      .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, tcpNoDelay)
      .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, tcpKeepAlive)
      .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, reuseAddr)
      .option[java.lang.Integer](ChannelOption.SO_LINGER, soLinger)
      .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, tcpConnectTimeout.inMillis.toInt)
      .childHandler(pipelineFactory)
    bs.childOption[ByteBufAllocator](ChannelOption.ALLOCATOR, sendAllocator)
    bs.childOption[RecvByteBufAllocator](ChannelOption.RCVBUF_ALLOCATOR, recvAllocator)
    bs
  }

  private[this] var listeners = Map[InetSocketAddress, ChannelFuture]()

  /**
   * Bind synchronous onto the given Socket Address
   * @param addr Inet Socket Address to listen on
   * @return this HttpServer for chained calling
   */
  def bind(addr: InetSocketAddress): HttpServer[Req] = synchronized {
    listeners += addr -> chan.bind(addr).awaitUninterruptibly()
    info("Listening on %s:%s", addr.getAddress.getHostAddress, addr.getPort)
    this
  }

  /**
   * Unbind synchronous from the given Socket Address
   * @param addr Inet Socket Address to unbind from
   * @return this HttpServer for chained calling
   */
  def unbind(addr: InetSocketAddress): HttpServer[Req] = synchronized {
    listeners.get(addr).foreach { cf =>
      cf.await()
      cf.channel().close()
      cf.channel().closeFuture().await()
      listeners = listeners - addr
      info("Removed listener on %s:%s", addr.getAddress.getHostAddress, addr.getPort)
    }
    this
  }

  /**
   * Shutdown this server and unbind all listeners
   */
  def shutdown(): Unit = synchronized {
    info("Shutting down")
    listeners.foreach {
      case (addr, cf) => unbind(addr)
    }
    listeners = Map.empty
  }
}

