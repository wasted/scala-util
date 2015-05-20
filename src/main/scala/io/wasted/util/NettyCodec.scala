package io.wasted.util

import com.twitter.util.{ Duration, Future }
import io.netty.channel.Channel

trait NettyCodec[Req, Resp] {
  def readTimeout: Option[Duration]
  def writeTimeout: Option[Duration]

  /**
   * Sets up basic Server-Pipeline for this Codec
   * @param channel Channel to apply the Pipeline to
   */
  def serverPipeline(channel: Channel): Unit

  /**
   * Sets up basic Client-Pipeline for this Codec
   * @param channel Channel to apply the Pipeline to
   */
  def clientPipeline(channel: Channel): Unit

  /**
   * Gets called once the TCP Connection has been established
   * with this being the API Client connecting to a Server
   * @param channel Channel we're connected to
   * @param request Request object we want to use
   * @return Future Response
   */
  def clientConnected(channel: Channel, request: Req): Future[Resp]
}