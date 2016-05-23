package io.wasted.util
package redis

import java.net.{ InetAddress, InetSocketAddress }

import com.twitter.util.{ Duration, Future }
import io.netty.channel._

/**
 * wasted.io Scala Redis Client
 * @param codec Redis Codec
 * @param remote Remote Host and Port
 * @param hostConnectionLimit Number of open connections for this client. Defaults to 1
 * @param globalTimeout Global Timeout for the completion of a request
 * @param tcpConnectTimeout TCP Connect Timeout
 * @param connectTimeout Timeout for establishing the Service
 * @param requestTimeout Timeout for each request
 * @param tcpKeepAlive TCP KeepAlive. Defaults to false
 * @param reuseAddr Reuse-Address. Defaults to true
 * @param tcpNoDelay TCP No-Delay. Defaults to true
 * @param soLinger soLinger. Defaults to 0
 * @param retries On connection or timeouts, how often should we retry? Defaults to 0
 * @param eventLoop Netty Event-Loop
 */
final case class RedisClient(codec: NettyRedisCodec = NettyRedisCodec(),
                             remote: List[InetSocketAddress] = List.empty,
                             hostConnectionLimit: Int = 1,
                             globalTimeout: Option[Duration] = None,
                             tcpConnectTimeout: Option[Duration] = None,
                             connectTimeout: Option[Duration] = None,
                             requestTimeout: Option[Duration] = None,
                             tcpKeepAlive: Boolean = false,
                             reuseAddr: Boolean = true,
                             tcpNoDelay: Boolean = true,
                             soLinger: Int = 0,
                             retries: Int = 0,
                             eventLoop: EventLoopGroup = Netty.eventLoop)
  extends NettyClientBuilder[java.net.URI, NettyRedisChannel] {

  def withSpecifics(codec: NettyRedisCodec) = copy(codec = codec)
  def withSoLinger(soLinger: Int) = copy(soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy(tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy(tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy(reuseAddr = reuseAddr)
  def withGlobalTimeout(globalTimeout: Duration) = copy(globalTimeout = Some(globalTimeout))
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy(tcpConnectTimeout = Some(tcpConnectTimeout))
  def withConnectTimeout(connectTimeout: Duration) = copy(connectTimeout = Some(connectTimeout))
  def withRequestTimeout(requestTimeout: Duration) = copy(requestTimeout = Some(requestTimeout))
  def withHostConnectionLimit(limit: Int) = copy(hostConnectionLimit = limit)
  def withEventLoop(eventLoop: EventLoopGroup) = copy(eventLoop = eventLoop)
  def connectTo(host: String, port: Int) = copy(remote = List(new InetSocketAddress(InetAddress.getByName(host), port)))
  def connectTo(hosts: List[InetSocketAddress]) = copy(remote = hosts)

  protected def getPort(url: java.net.URI): Int = if (url.getPort > 0) url.getPort else 6379

  private[redis] def connect(): Future[NettyRedisChannel] = {
    val rand = scala.util.Random.nextInt(remote.length)
    val host = remote(rand)
    val uri = new java.net.URI("redis://" + host.getHostString + ":" + host.getPort)
    write(uri, uri)
  }

  def open(): Future[NettyRedisChannel] = {
    connect().map { redisChannel =>
      redisChannel.setClient(this)
      redisChannel
    }
  }
}
