package io.wasted.util

import java.util.concurrent.TimeUnit

import com.twitter.concurrent.{Broker, Offer}
import com.twitter.util.{Duration, Future, Promise}
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{SslContext, SslContextBuilder}
import io.netty.handler.timeout.{ReadTimeoutHandler, WriteTimeoutHandler}
import io.wasted.util.http.HttpClient.Handlers

/**
 * ByteArray Channel
 * @param out Outbound Broker
 * @param in Inbound Offer
 * @param channel Netty Channel
 */
final case class NettyByteArrayChannel(out: Broker[ByteBuf], in: Offer[ByteBuf], private val channel: Channel) {
  def close(): Future[Unit] = {
    val closed = Promise[Unit]()
    channel.closeFuture().addListener(new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = {
        closed.setDone()
      }
    })
    closed.raiseWithin(Duration(2, TimeUnit.SECONDS))(WheelTimer.twitter)
  }

  val onDisconnect = Promise[Unit]()
  channel.closeFuture().addListener(new ChannelFutureListener {
    override def operationComplete(f: ChannelFuture): Unit = onDisconnect.setDone()
  })

  def !(s: ByteBuf): Unit = out ! s
  def !(s: Array[Byte]): Unit = out ! channel.alloc().buffer(s.length).writeBytes(s)
  def foreach(run: ByteBuf => Unit) = in.foreach(run)
}

/**
 * wasted.io Scala ByteArray Codec
 *
 * For composition you may use ByteArrayCodec().withTls(..)
 *
 * @param readTimeout Channel Read Timeout
 * @param writeTimeout Channel Write Timeout
 * @param sslCtx Netty SSL Context
 */
final case class NettyByteArrayCodec(readTimeout: Option[Duration] = None,
                                     writeTimeout: Option[Duration] = None,
                                     sslCtx: Option[SslContext] = None) extends NettyCodec[ByteBuf, NettyByteArrayChannel] {

  def withTls(sslCtx: SslContext) = copy(sslCtx = Some(sslCtx))
  def withReadTimeout(timeout: Duration) = copy(readTimeout = Some(timeout))
  def withWriteTimeout(timeout: Duration) = copy(writeTimeout = Some(timeout))

  def withInsecureTls() = {
    val ctx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    copy(sslCtx = Some(ctx))
  }

  /**
   * Sets up basic server Pipeline
   * @param channel Channel to apply the Pipeline to
   */
  def serverPipeline(channel: Channel): Unit = ()

  /**
   * Sets up basic client Pipeline
   * @param channel Channel to apply the Pipeline to
   */
  def clientPipeline(channel: Channel): Unit = ()

  /**
   * Handle the connected channel and send the request
   * @param channel Channel we're connected to
   * @param request Object we want to send
   * @return Future Output Broker and Inbound Offer and Close Function
   */
  def clientConnected(channel: Channel, request: ByteBuf): Future[NettyByteArrayChannel] = {
    val inBroker = new Broker[ByteBuf]
    val outBroker = new Broker[ByteBuf]
    val result = Promise[NettyByteArrayChannel]

    readTimeout.foreach { readTimeout =>
      channel.pipeline.addFirst(Handlers.readTimeout, new ReadTimeoutHandler(readTimeout.inMillis.toInt) {
        override def readTimedOut(ctx: ChannelHandlerContext) {
          ctx.channel.close
          result.setException(new IllegalStateException("Read timed out"))
        }
      })
    }
    writeTimeout.foreach { writeTimeout =>
      channel.pipeline.addFirst(Handlers.writeTimeout, new WriteTimeoutHandler(writeTimeout.inMillis.toInt) {
        override def writeTimedOut(ctx: ChannelHandlerContext) {
          ctx.channel.close
          result.setException(new IllegalStateException("Write timed out"))
        }
      })
    }

    channel.pipeline.addLast(Handlers.handler, new SimpleChannelInboundHandler[ByteBuf] {
      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        http.ExceptionHandler(ctx, cause)
        result.setException(cause)
        ctx.channel.close
      }

      def channelRead0(ctx: ChannelHandlerContext, msg: ByteBuf) {
        // if our future has not been filled yet (first response), we reply with the brokers
        if (!result.isDefined) result.setValue(NettyByteArrayChannel(outBroker, inBroker.recv, channel))

        // we wire the inbound packet to the Broker
        inBroker ! msg.retain()
      }
    })

    // we wire the outbound broker to send to the channel
    outBroker.recv.foreach(buf => channel.writeAndFlush(buf))
    Option(request).map(outBroker ! _)

    result
  }
}