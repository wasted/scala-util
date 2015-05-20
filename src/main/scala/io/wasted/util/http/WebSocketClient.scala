package io.wasted.util
package http

import java.net.{ InetAddress, InetSocketAddress }

import com.twitter.util.Duration
import io.netty.channel._

/**
 * wasted.io Scala WebSocket Client
 * @param codec Http Codec
 * @param remote Remote Host and Port
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
final case class WebSocketClient(codec: NettyWebSocketCodec = NettyWebSocketCodec(),
                                 subprotocols: String = null,
                                 allowExtensions: Boolean = true,
                                 remote: List[InetSocketAddress] = List.empty,
                                 globalTimeout: Option[Duration] = None,
                                 tcpConnectTimeout: Option[Duration] = None,
                                 connectTimeout: Option[Duration] = None,
                                 requestTimeout: Option[Duration] = None,
                                 tcpKeepAlive: Boolean = false,
                                 reuseAddr: Boolean = true,
                                 tcpNoDelay: Boolean = true,
                                 soLinger: Int = 0,
                                 retries: Int = 0,
                                 eventLoop: EventLoopGroup = Netty.eventLoop)(implicit wheelTimer: WheelTimer)
  extends NettyClientBuilder[java.net.URI, NettyWebSocketChannel] {

  def withExtensions(allowExtensions: Boolean) = copy(allowExtensions = allowExtensions)
  def withSubprotocols(subprotocols: String) = copy(subprotocols = subprotocols)
  def withSpecifics(codec: NettyWebSocketCodec) = copy(codec = codec)
  def withSoLinger(soLinger: Int) = copy(soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy(tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy(tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy(reuseAddr = reuseAddr)
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy(tcpConnectTimeout = Some(tcpConnectTimeout))
  def withEventLoop(eventLoop: EventLoopGroup) = copy(eventLoop = eventLoop)
  def withRetries(retries: Int) = copy(retries = retries)
  def connectTo(host: String, port: Int) = copy(remote = List(new InetSocketAddress(InetAddress.getByName(host), port)))
  def connectTo(hosts: List[InetSocketAddress]) = copy(remote = hosts)

  protected def getPort(url: java.net.URI): Int = if (url.getPort > 0) url.getPort else url.getScheme match {
    case "http" => 80
    case "https" => 443
  }
}