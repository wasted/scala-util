package io.wasted.util

import com.twitter.conversions.time._
import com.twitter.util.{ Duration, Future }
import io.netty.buffer.{ PooledByteBufAllocator, ByteBuf, ByteBufAllocator }
import io.netty.channel._

object NettyTcpServer {
  private[util] val defaultHandler: (Channel, Future[ByteBuf]) => Future[ByteBuf] = { (ch, msg) =>
    Future.value(null)
  }
}

/**
 * wasted.io Scala Server
 * @param codec Http Codec
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
case class NettyTcpServer[Req, Resp](codec: NettyCodec[Req, Resp],
                                     tcpConnectTimeout: Duration = 5.seconds,
                                     tcpKeepAlive: Boolean = false,
                                     reuseAddr: Boolean = true,
                                     tcpNoDelay: Boolean = true,
                                     soLinger: Int = 0,
                                     sendAllocator: ByteBufAllocator = PooledByteBufAllocator.DEFAULT,
                                     recvAllocator: RecvByteBufAllocator = new AdaptiveRecvByteBufAllocator,
                                     parentLoop: EventLoopGroup = Netty.eventLoop,
                                     childLoop: EventLoopGroup = Netty.eventLoop,
                                     pipeline: Channel => Unit = p => (),
                                     handle: (Channel, Future[Req]) => Future[Resp] = NettyTcpServer.defaultHandler)
  extends NettyServerBuilder[NettyTcpServer[Req, Resp], Req, Resp] {

  def withSoLinger(soLinger: Int) = copy[Req, Resp](soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy[Req, Resp](tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy[Req, Resp](tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy[Req, Resp](reuseAddr = reuseAddr)
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy[Req, Resp](tcpConnectTimeout = tcpConnectTimeout)
  def withPipeline(pipeline: (Channel) => Unit) = copy[Req, Resp](pipeline = pipeline)
  def handler(handle: (Channel, Future[Req]) => Future[Resp]) = copy[Req, Resp](handle = handle)

  def withEventLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](parentLoop = eventLoop, childLoop = eventLoop)
  def withEventLoop(parentLoop: EventLoopGroup, childLoop: EventLoopGroup) = copy[Req, Resp](parentLoop = parentLoop, childLoop = childLoop)
  def withChildLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](childLoop = eventLoop)
  def withParentLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](parentLoop = eventLoop)

}

