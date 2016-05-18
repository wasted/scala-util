package io.wasted.util
package http

import com.twitter.conversions.storage._
import com.twitter.util._
import io.netty.channel.{Channel, ChannelHandlerContext, SimpleChannelInboundHandler}
import io.netty.handler.codec.http._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{SslContext, SslContextBuilder}
import io.netty.handler.timeout.{ReadTimeoutHandler, WriteTimeoutHandler}
import io.netty.util.ReferenceCountUtil

/**
 * wasted.io Scala Http Codec
 *
 * For composition you may use HttpCodec[HttpObject]().withChunking(true)...
 *
 * @param compressionLevel GZip compression level
 * @param decompression GZip decompression?
 * @param keepAlive TCP KeepAlive. Defaults to false
 * @param chunked Should we pass through chunks?
 * @param chunking Should we allow chunking?
 * @param maxChunkSize Maximum chunk size
 * @param maxRequestSize Maximum request size
 * @param maxResponseSize Maximum response size
 * @param maxInitialLineLength Maximum line length for GET/POST /foo...
 * @param maxHeaderSize Maximum header size
 * @param readTimeout Channel Read Timeout
 * @param writeTimeout Channel Write Timeout
 * @param sslCtx Netty SSL Context
 */
final case class NettyHttpCodec[Req <: HttpMessage, Resp <: HttpObject](compressionLevel: Int = -1,
                                                                        decompression: Boolean = true,
                                                                        keepAlive: Boolean = false,
                                                                        chunked: Boolean = false,
                                                                        chunking: Boolean = true,
                                                                        maxChunkSize: StorageUnit = 5.megabytes,
                                                                        maxRequestSize: StorageUnit = 5.megabytes,
                                                                        maxResponseSize: StorageUnit = 5.megabytes,
                                                                        maxInitialLineLength: StorageUnit = 4096.bytes,
                                                                        maxHeaderSize: StorageUnit = 8192.bytes,
                                                                        readTimeout: Option[Duration] = None,
                                                                        writeTimeout: Option[Duration] = None,
                                                                        sslCtx: Option[SslContext] = None) extends NettyCodec[Req, Resp] {

  def withChunking(chunking: Boolean, chunked: Boolean = false, maxChunkSize: StorageUnit = 5.megabytes) =
    copy[Req, Resp](chunking = chunking, chunked = chunked, maxChunkSize = maxChunkSize)

  def withCompression(compressionLevel: Int) = copy[Req, Resp](compressionLevel = compressionLevel)
  def withDecompression(decompression: Boolean) = copy[Req, Resp](decompression = decompression)
  def withMaxRequestSize(maxRequestSize: StorageUnit) = copy[Req, Resp](maxRequestSize = maxRequestSize)
  def withMaxResponseSize(maxResponseSize: StorageUnit) = copy[Req, Resp](maxResponseSize = maxResponseSize)
  def withMaxInitialLineLength(maxInitialLineLength: StorageUnit) = copy[Req, Resp](maxInitialLineLength = maxInitialLineLength)
  def withMaxHeaderSize(maxHeaderSize: StorageUnit) = copy[Req, Resp](maxHeaderSize = maxHeaderSize)
  def withTls(sslCtx: SslContext) = copy[Req, Resp](sslCtx = Some(sslCtx))
  def withKeepAlive(keepAlive: Boolean) = copy[Req, Resp](keepAlive = keepAlive)
  def withReadTimeout(timeout: Duration) = copy[Req, Resp](readTimeout = Some(timeout))
  def withWriteTimeout(timeout: Duration) = copy[Req, Resp](writeTimeout = Some(timeout))

  def withInsecureTls() = {
    val ctx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    copy[Req, Resp](sslCtx = Some(ctx))
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
    val maxContentLength = this.maxResponseSize.inBytes.toInt
    p.addLast(HttpServer.Handlers.codec, new HttpServerCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize.inBytes.toInt))
    if (compressionLevel > 0) {
      p.addLast(HttpServer.Handlers.compressor, new HttpContentCompressor(compressionLevel))
    }
    if (chunking && !chunked) {
      p.addLast(HttpServer.Handlers.dechunker, new HttpObjectAggregator(maxContentLength))
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
    val maxContentLength = this.maxResponseSize.inBytes.toInt
    pipeline.addLast(HttpClient.Handlers.codec, new HttpClientCodec(maxInitialBytes, maxHeaderBytes, maxChunkSize))
    if (chunking && !chunked) {
      pipeline.addLast(HttpClient.Handlers.aggregator, new HttpObjectAggregator(maxContentLength))
    }
    if (decompression) {
      pipeline.addLast(HttpClient.Handlers.decompressor, new HttpContentDecompressor())
    }
  }

  /**
   * Handle the connected channel and send the request
   * @param channel Channel we're connected to
   * @param request Object we want to send
   * @return
   */
  def clientConnected(channel: Channel, request: Req): Future[Resp] = {
    val result = Promise[Resp]
    readTimeout.foreach { readTimeout =>
      channel.pipeline.addFirst(HttpClient.Handlers.readTimeout, new ReadTimeoutHandler(readTimeout.inMillis.toInt) {
        override def readTimedOut(ctx: ChannelHandlerContext) {
          ctx.channel.close
          result.setException(new IllegalStateException("Read timed out"))
        }
      })
    }
    writeTimeout.foreach { writeTimeout =>
      channel.pipeline.addFirst(HttpClient.Handlers.writeTimeout, new WriteTimeoutHandler(writeTimeout.inMillis.toInt) {
        override def writeTimedOut(ctx: ChannelHandlerContext) {
          ctx.channel.close
          result.setException(new IllegalStateException("Write timed out"))
        }
      })
    }

    channel.pipeline.addLast(HttpClient.Handlers.handler, new SimpleChannelInboundHandler[Resp] {
      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ExceptionHandler(ctx, cause)
        result.setException(cause)
        ctx.channel.close
      }

      def channelRead0(ctx: ChannelHandlerContext, msg: Resp) {
        if (!result.isDefined && result.isInterrupted.isEmpty) result.setValue(ReferenceCountUtil.retain(msg))
        if (keepAlive && HttpUtil.isKeepAlive(request)) {} else channel.close()
      }
    })
    val ka = if (keepAlive && HttpUtil.isKeepAlive(request))
      HttpHeaderValues.KEEP_ALIVE else HttpHeaderValues.CLOSE
    request.headers().set(HttpHeaderNames.CONNECTION, ka)

    channel.writeAndFlush(request)
    result
  }
}