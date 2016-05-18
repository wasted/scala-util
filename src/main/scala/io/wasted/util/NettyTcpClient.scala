package io.wasted.util

import java.net.{InetAddress, InetSocketAddress}

import com.twitter.util.Duration
import io.netty.channel._

/**
 * wasted.io Scala Netty TCP Client
 * @param codec Codec
 * @param remote Remote Host and Port
 * @param hostConnectionLimit Number of open connections for this client. Defaults to 1
 * @param hostConnectionCoreSize Number of connections to keep open for this client. Defaults to 0
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
 * @param pipeline Setup extra handlers on the Netty Pipeline
 */
case class NettyTcpClient[Req, Resp](codec: NettyCodec[Req, Resp],
                                     remote: List[InetSocketAddress] = List.empty,
                                     hostConnectionLimit: Int = 1,
                                     hostConnectionCoreSize: Int = 0,
                                     globalTimeout: Option[Duration] = None,
                                     tcpConnectTimeout: Option[Duration] = None,
                                     connectTimeout: Option[Duration] = None,
                                     requestTimeout: Option[Duration] = None,
                                     tcpKeepAlive: Boolean = false,
                                     reuseAddr: Boolean = true,
                                     tcpNoDelay: Boolean = true,
                                     soLinger: Int = 0,
                                     retries: Int = 0,
                                     eventLoop: EventLoopGroup = Netty.eventLoop,
                                     pipeline: Channel => Unit = p => ()) extends NettyClientBuilder[Req, Resp] {
  def withSoLinger(soLinger: Int) = copy[Req, Resp](soLinger = soLinger)
  def withTcpNoDelay(tcpNoDelay: Boolean) = copy[Req, Resp](tcpNoDelay = tcpNoDelay)
  def withTcpKeepAlive(tcpKeepAlive: Boolean) = copy[Req, Resp](tcpKeepAlive = tcpKeepAlive)
  def withReuseAddr(reuseAddr: Boolean) = copy[Req, Resp](reuseAddr = reuseAddr)
  def withGlobalTimeout(globalTimeout: Duration) = copy[Req, Resp](globalTimeout = Some(globalTimeout))
  def withTcpConnectTimeout(tcpConnectTimeout: Duration) = copy[Req, Resp](tcpConnectTimeout = Some(tcpConnectTimeout))
  def withConnectTimeout(connectTimeout: Duration) = copy[Req, Resp](connectTimeout = Some(connectTimeout))
  def withRequestTimeout(requestTimeout: Duration) = copy[Req, Resp](requestTimeout = Some(requestTimeout))
  def withHostConnectionLimit(limit: Int) = copy[Req, Resp](hostConnectionLimit = limit)
  def withHostConnectionCoresize(coreSize: Int) = copy[Req, Resp](hostConnectionCoreSize = coreSize)
  def withRetries(retries: Int) = copy[Req, Resp](retries = retries)
  def withEventLoop(eventLoop: EventLoopGroup) = copy[Req, Resp](eventLoop = eventLoop)
  def withPipeline(pipeline: Channel => Unit) = copy[Req, Resp](pipeline = pipeline)
  def connectTo(host: String, port: Int) = copy[Req, Resp](remote = List(new InetSocketAddress(InetAddress.getByName(host), port)))
  def connectTo(hosts: List[InetSocketAddress]) = copy[Req, Resp](remote = hosts)

  def getPort(uri: java.net.URI): Int = remote.headOption.map(_.getPort).getOrElse {
    throw new IllegalArgumentException("No port was given through hosts Parameter")
  }
}

