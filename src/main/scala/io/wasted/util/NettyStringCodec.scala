package io.wasted.util

import java.util.concurrent.TimeUnit

import com.twitter.concurrent.{ Broker, Offer }
import com.twitter.util.{ Duration, Future, Promise }
import io.netty.channel._
import io.netty.handler.codec.string.{ StringDecoder, StringEncoder }
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{ SslContext, SslContextBuilder }
import io.netty.handler.timeout.{ ReadTimeoutHandler, WriteTimeoutHandler }
import io.netty.util.CharsetUtil
import io.wasted.util.http.HttpClient.Handlers

/**
 * String Channel
 * @param out Outbound Broker
 * @param in Inbound Offer
 * @param channel Netty Channel
 */
final case class NettyStringChannel(out: Broker[String], in: Offer[String], private val channel: Channel) {
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

  def !(s: String): Unit = out ! s
  def foreach(run: String => Unit) = in.foreach(run)
}

/**
 * wasted.io Scala String Codec
 *
 * For composition you may use StringCodec().withTls(..)
 *
 * @param readTimeout Channel Read Timeout
 * @param writeTimeout Channel Write Timeout
 * @param sslCtx Netty SSL Context
 */
final case class NettyStringCodec(readTimeout: Option[Duration] = None,
                                  writeTimeout: Option[Duration] = None,
                                  sslCtx: Option[SslContext] = None) extends NettyCodec[String, NettyStringChannel] {

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
  def serverPipeline(channel: Channel): Unit = clientPipeline(channel)

  /**
   * Sets up basic client Pipeline
   * @param channel Channel to apply the Pipeline to
   */
  def clientPipeline(channel: Channel): Unit = {
    val pipeline = channel.pipeline()
    pipeline.addLast("stringDecoder", new StringDecoder(CharsetUtil.UTF_8))
    pipeline.addLast("stringEncoder", new StringEncoder(CharsetUtil.UTF_8))
  }

  /**
   * Handle the connected channel and send the request
   * @param channel Channel we're connected to
   * @param request Object we want to send
   * @return Future Output Broker and Inbound Offer and Close Function
   */
  def clientConnected(channel: Channel, request: String): Future[NettyStringChannel] = {
    val inBroker = new Broker[String]
    val outBroker = new Broker[String]
    val result = Promise[NettyStringChannel]

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

    channel.pipeline.addLast(Handlers.handler, new SimpleChannelInboundHandler[String] {
      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        http.ExceptionHandler(ctx, cause)
        result.setException(cause)
        ctx.channel.close
      }

      def channelRead0(ctx: ChannelHandlerContext, msg: String) {
        // if our future has not been filled yet (first response), we reply with the brokers
        if (!result.isDefined) result.setValue(NettyStringChannel(outBroker, inBroker.recv, channel))

        // we wire the inbound packet to the Broker
        inBroker ! msg
      }
    })

    // we wire the outbound broker to send to the channel
    outBroker.recv.foreach(buf => channel.writeAndFlush(buf))
    Option(request).map(outBroker ! _)

    result
  }
}