package io.wasted.util

import java.net.InetSocketAddress

import com.twitter.util._
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufAllocator
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel

trait NettyServerBuilder[T, Req, Resp] extends Logger { self: T =>
  def codec: NettyCodec[Req, Resp]
  def tcpConnectTimeout: Duration
  def tcpKeepAlive: Boolean
  def reuseAddr: Boolean
  def tcpNoDelay: Boolean
  def soLinger: Int
  def sendAllocator: ByteBufAllocator
  def recvAllocator: RecvByteBufAllocator
  def parentLoop: EventLoopGroup
  def childLoop: EventLoopGroup
  def pipeline: Channel => Unit
  def handle: (Channel, Future[Req]) => Future[Resp]

  protected[this] lazy val srv = new ServerBootstrap()
  protected[this] lazy val chan = {
    val pipelineFactory = new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel): Unit = {
        codec.serverPipeline(ch)
        pipeline(ch)
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
  def bind(addr: InetSocketAddress): T = synchronized {
    listeners += addr -> chan.bind(addr).awaitUninterruptibly()
    info("Listening on %s:%s", addr.getAddress.getHostAddress, addr.getPort)
    self
  }

  /**
   * Unbind synchronous from the given Socket Address
   * @param addr Inet Socket Address to unbind from
   * @return this HttpServer for chained calling
   */
  def unbind(addr: InetSocketAddress): T = synchronized {
    listeners.get(addr).foreach { cf =>
      cf.await()
      cf.channel().close()
      cf.channel().closeFuture().await()
      listeners = listeners - addr
      info("Removed listener on %s:%s", addr.getAddress.getHostAddress, addr.getPort)
    }
    self
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
