package io.wasted.util
package http

import java.util.concurrent.TimeUnit

import com.twitter.concurrent.{ Broker, Offer }
import com.twitter.conversions.storage._
import com.twitter.conversions.time._
import com.twitter.util._
import io.netty.buffer.ByteBuf
import io.netty.channel._
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.{ BinaryWebSocketFrame, WebSocketClientHandshakerFactory, WebSocketFrame, WebSocketVersion }
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{ SslContext, SslContextBuilder }
import io.netty.handler.timeout.{ ReadTimeoutHandler, WriteTimeoutHandler }

/**
 * String Channel
 * @param out Outbound Broker
 * @param in Inbound Offer
 * @param channel Netty Channel
 */
final case class NettyWebSocketChannel(out: Broker[WebSocketFrame], in: Offer[WebSocketFrame], private val channel: Channel) {
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

  def !(s: ByteBuf): Unit = out ! new BinaryWebSocketFrame(s)
  def !(s: Array[Byte]): Unit = out ! new BinaryWebSocketFrame(channel.alloc().buffer(s.length).writeBytes(s))
  def !(s: WebSocketFrame): Unit = out ! s
  def foreach(run: WebSocketFrame => Unit) = in.foreach(run)
}

/**
 * wasted.io Scala WebSocket Codec
 *
 * For composition you may use NettyWebSocketCodec()...
 *
 * @param compressionLevel GZip compression level
 * @param decompression GZip decompression?
 * @param subprotocols Subprotocols which to support
 * @param allowExtensions Allow Extensions
 * @param keepAlive TCP KeepAlive. Defaults to false
 * @param maxChunkSize Maximum chunk size
 * @param maxRequestSize Maximum request size
 * @param maxResponseSize Maximum response size
 * @param maxInitialLineLength Maximum line length for GET/POST /foo...
 * @param maxHeaderSize Maximum header size
 * @param readTimeout Channel Read Timeout
 * @param writeTimeout Channel Write Timeout
 * @param sslCtx Netty SSL Context
 */
final case class NettyWebSocketCodec(compressionLevel: Int = -1,
                                     subprotocols: String = null,
                                     allowExtensions: Boolean = true,
                                     decompression: Boolean = true,
                                     keepAlive: Boolean = false,
                                     maxChunkSize: StorageUnit = 5.megabytes,
                                     maxRequestSize: StorageUnit = 5.megabytes,
                                     maxResponseSize: StorageUnit = 5.megabytes,
                                     maxInitialLineLength: StorageUnit = 4096.bytes,
                                     maxHeaderSize: StorageUnit = 8192.bytes,
                                     readTimeout: Option[Duration] = None,
                                     writeTimeout: Option[Duration] = None,
                                     sslCtx: Option[SslContext] = None)
  extends NettyCodec[java.net.URI, NettyWebSocketChannel] {

  def withCompression(compressionLevel: Int) = copy(compressionLevel = compressionLevel)
  def withDecompression(decompression: Boolean) = copy(decompression = decompression)
  def withMaxRequestSize(maxRequestSize: StorageUnit) = copy(maxRequestSize = maxRequestSize)
  def withMaxResponseSize(maxResponseSize: StorageUnit) = copy(maxResponseSize = maxResponseSize)
  def withMaxInitialLineLength(maxInitialLineLength: StorageUnit) = copy(maxInitialLineLength = maxInitialLineLength)
  def withMaxHeaderSize(maxHeaderSize: StorageUnit) = copy(maxHeaderSize = maxHeaderSize)
  def withTls(sslCtx: SslContext) = copy(sslCtx = Some(sslCtx))
  def withKeepAlive(keepAlive: Boolean) = copy(keepAlive = keepAlive)
  def withReadTimeout(timeout: Duration) = copy(readTimeout = Some(timeout))
  def withWriteTimeout(timeout: Duration) = copy(writeTimeout = Some(timeout))

  def withInsecureTls() = {
    val ctx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    copy(sslCtx = Some(ctx))
  }

  /**
   * Sets up basic Server-Pipeline for this Codec
   * @param channel Channel to apply the Pipeline to
   */
  def serverPipeline(channel: Channel): Unit = {
    val p = channel.pipeline()
    sslCtx.foreach(e => p.addLast(HttpServer.Handlers.ssl, e.newHandler(channel.alloc())))
    val maxInitialBytes = maxInitialLineLength.inBytes.toInt
    val maxHeaderBytes = maxHeaderSize.inBytes.toInt
    p.addLast(HttpServer.Handlers.codec, new HttpServerCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize.inBytes.toInt))
    if (compressionLevel > 0) {
      p.addLast(HttpServer.Handlers.compressor, new HttpContentCompressor(compressionLevel))
    }
  }

  /**
   * Sets up basic HTTP Pipeline
   * @param channel Channel to apply the Pipeline to
   */
  def clientPipeline(channel: Channel): Unit = {
    val pipeline = channel.pipeline()
    sslCtx.foreach(e => pipeline.addLast(HttpServer.Handlers.ssl, e.newHandler(channel.alloc())))
    val maxInitialBytes = this.maxInitialLineLength.inBytes.toInt
    val maxHeaderBytes = this.maxHeaderSize.inBytes.toInt
    val maxChunkSize = this.maxChunkSize.inBytes.toInt
    pipeline.addLast(HttpClient.Handlers.codec, new HttpClientCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize))
    if (decompression) {
      pipeline.addLast(HttpClient.Handlers.decompressor, new HttpContentDecompressor())
    }
    pipeline.addLast(HttpClient.Handlers.aggregator, new HttpObjectAggregator(maxChunkSize))
  }

  /**
   * Handle the connected channel and send the request
   * @param channel Channel we're connected to
   * @param uri URI We want to use
   * @return
   */
  def clientConnected(channel: Channel, uri: java.net.URI): Future[NettyWebSocketChannel] = {
    val inBroker = new Broker[WebSocketFrame]
    val outBroker = new Broker[WebSocketFrame]
    val result = Promise[NettyWebSocketChannel]

    readTimeout.foreach { readTimeout =>
      channel.pipeline.addFirst(HttpClient.Handlers.readTimeout, new ReadTimeoutHandler(readTimeout.inMillis.toInt) {
        override def readTimedOut(ctx: ChannelHandlerContext) {
          ctx.channel.close
          if (!result.isDefined) result.setException(new IllegalStateException("Read timed out"))
        }
      })
    }
    writeTimeout.foreach { writeTimeout =>
      channel.pipeline.addFirst(HttpClient.Handlers.writeTimeout, new WriteTimeoutHandler(writeTimeout.inMillis.toInt) {
        override def writeTimedOut(ctx: ChannelHandlerContext) {
          ctx.channel.close
          if (!result.isDefined) result.setException(new IllegalStateException("Write timed out"))
        }
      })
    }

    val headers = new DefaultHttpHeaders()
    val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
      uri, WebSocketVersion.V13, subprotocols, true, headers)

    channel.pipeline().addLast(new SimpleChannelInboundHandler[FullHttpResponse] {
      override def channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
        if (!handshaker.isHandshakeComplete) {
          handshaker.finishHandshake(ctx.channel(), msg)
          ctx.channel().pipeline().remove(this)
        } else ctx.channel().close() // this should not happen
      }

      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        if (!result.isDefined) result.setException(cause)
        ctx.close()
      }
    })

    channel.pipeline().addLast(HttpClient.Handlers.handler, new SimpleChannelInboundHandler[WebSocketFrame] {
      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
        if (!result.isDefined) result.setException(cause)
      }

      override def channelRead0(ctx: ChannelHandlerContext, msg: WebSocketFrame): Unit = {
        // we wire the inbound packet to the Broker
        inBroker ! msg.retain()
      }
    })

    val promise = channel.newPromise()
    handshaker.handshake(channel, promise)
    promise.addListener(new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = {
        // don't overflow the server immediately after handshake
        implicit val timer = WheelTimer
        Schedule(() => {
          // we wire the outbound broker to send to the channel
          outBroker.recv.foreach(buf => channel.writeAndFlush(buf))
          // return the future
          if (!result.isDefined) result.setValue(NettyWebSocketChannel(outBroker, inBroker.recv, channel))
        }, 100.millis)
      }
    })

    result
  }
}