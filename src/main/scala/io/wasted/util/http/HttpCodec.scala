package io.wasted.util
package http

import com.twitter.conversions.storage._
import com.twitter.conversions.time._
import com.twitter.util.{ Duration, StorageUnit }
import io.netty.handler.codec.http._
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.ssl.{ SslContext, SslContextBuilder }

/**
 * wasted.io Scala Http Codec
 *
 * For composition you may use HttpCodec[HttpObject]().withChunking(true)...
 *
 * @param compressionLevel GZip compression level
 * @param decompression GZip decompression?
 * @param chunked Should we pass through chunks?
 * @param chunking Should we allow chunking?
 * @param maxChunkSize Maximum chunk size
 * @param maxRequestSize Maximum request size
 * @param maxResponseSize Maximum response size
 */
final case class HttpCodec[T <: HttpObject](compressionLevel: Int = -1,
                                            decompression: Boolean = true,
                                            chunked: Boolean = false,
                                            chunking: Boolean = true,
                                            readTimeout: Duration = 30.seconds,
                                            maxChunkSize: StorageUnit = 5.megabytes,
                                            maxRequestSize: StorageUnit = 5.megabytes,
                                            maxResponseSize: StorageUnit = 5.megabytes,
                                            maxInitialLineLength: StorageUnit = 4096.bytes,
                                            maxHeaderSize: StorageUnit = 8192.bytes,
                                            sslCtx: Option[SslContext] = None) {

  def withReadTimeout(readTimeout: Duration) = copy[T](readTimeout = readTimeout)

  def withChunking(chunking: Boolean, chunked: Boolean = false, maxChunkSize: StorageUnit = 5.megabytes) =
    copy[T](chunking = chunking, chunked = chunked, maxChunkSize = maxChunkSize)

  def withCompression(compressionLevel: Int) = copy[T](compressionLevel = compressionLevel)

  def withDecompression(decompression: Boolean) = copy[T](decompression = decompression)

  def withMaxRequestSize(maxRequestSize: StorageUnit) = copy[T](maxRequestSize = maxRequestSize)

  def withMaxResponseSize(maxResponseSize: StorageUnit) = copy[T](maxResponseSize = maxResponseSize)

  def withMaxInitialLineLength(maxInitialLineLength: StorageUnit) = copy[T](maxInitialLineLength = maxInitialLineLength)

  def withMaxHeaderSize(maxHeaderSize: StorageUnit) = copy[T](maxHeaderSize = maxHeaderSize)

  def withTls(sslCtx: SslContext) = copy[T](sslCtx = Some(sslCtx))

  def withInsecureTls() = {
    val ctx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
    copy[T](sslCtx = Some(ctx))
  }
}