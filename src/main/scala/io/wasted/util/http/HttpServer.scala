package io.wasted.util.http

import com.twitter.conversions.time._
import com.twitter.util._
import io.netty.buffer._
import io.netty.channel._
import io.netty.handler.codec.http._
import io.wasted.util._

object HttpServer {
  private[http] val defaultHandler: (Channel, Future[HttpMessage]) => Future[HttpResponse] = { (ch, msg) =>
    Future.value(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND))
  }

  val closeListener = new ChannelFutureListener {
    override def operationComplete(f: ChannelFuture): Unit = {
      f.channel().disconnect()
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
 * @param customPipeline Setup extra handlers on the Netty Pipeline
 * @param handle Service to handle HttpRequests
 */
final case class HttpServer[Req <: HttpMessage, Resp <: HttpResponse](codec: NettyHttpCodec[Req, Resp] = NettyHttpCodec[Req, Resp](),
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
                                                                      customPipeline: Channel => Unit = p => (),
                                                                      handle: (Channel, Future[Req]) => Future[Resp] = HttpServer.defaultHandler)
  extends NettyServerBuilder[HttpServer[Req, Resp], Req, Resp] with Logger {

  def withSoLinger(soLinger: Int) = copy[Req, Resp](soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy[Req, Resp](tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy[Req, Resp](tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy[Req, Resp](reuseAddr = reuseAddr)
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy[Req, Resp](tcpConnectTimeout = tcpConnectTimeout)
  def withPipeline(pipeline: (Channel) => Unit) = copy[Req, Resp](customPipeline = pipeline)
  def handler(handle: (Channel, Future[Req]) => Future[Resp]) = copy[Req, Resp](handle = handle)

  def withEventLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](parentLoop = eventLoop, childLoop = eventLoop)
  def withEventLoop(parentLoop: EventLoopGroup, childLoop: EventLoopGroup) = copy[Req, Resp](parentLoop = parentLoop, childLoop = childLoop)
  def withChildLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](childLoop = eventLoop)
  def withParentLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](parentLoop = eventLoop)

  val pipeline: Channel => Unit = (channel: Channel) => {
    customPipeline(channel)
    channel.pipeline().addLast(HttpServer.Handlers.handler, new SimpleChannelInboundHandler[Req] {
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
              if (HttpHeaders.isKeepAlive(req)) HttpHeaders.Values.KEEP_ALIVE
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
  }

}

