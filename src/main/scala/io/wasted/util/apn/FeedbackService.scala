package io.wasted.util.apn

import java.net.InetSocketAddress

import io.netty.bootstrap._
import io.netty.buffer._
import io.netty.channel.ChannelHandler.Sharable
import io.netty.channel._
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.ssl.SslHandler
import io.wasted.util._

/**
 * Feedback Service response
 * @param token Token of the device
 * @param expired When the device expired
 */
case class Feedback(token: String, expired: java.util.Date)

/**
 * Apple Push Notification Push class which will handle all delivery.
 *
 * @param params Connection Parameters
 */
@Sharable
class FeedbackService(params: Params, function: Feedback => AnyVal)
  extends SimpleChannelInboundHandler[ByteBuf] with Logger { thisService =>
  override val loggerName = params.name

  private final val production = new InetSocketAddress(java.net.InetAddress.getByName("feedback.push.apple.com"), 2196)
  private final val sandbox = new InetSocketAddress(java.net.InetAddress.getByName("feedback.sandbox.push.apple.com"), 2196)
  val addr: InetSocketAddress = if (params.sandbox) sandbox else production

  private val srv = new Bootstrap()
  private val bootstrap = srv.group(Netty.eventLoop)
    .channel(classOf[NioSocketChannel])
    .remoteAddress(addr)
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, params.timeout * 1000)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        p.addLast("ssl", new SslHandler(params.createSSLEngine(addr.getAddress.getHostAddress, addr.getPort)))
        p.addLast("handler", thisService)
      }
    })

  def run(): Boolean = Tryo(bootstrap.clone.connect().sync().channel()).isDefined

  override def channelRead0(ctx: ChannelHandlerContext, buf: ByteBuf) {
    if (buf.readableBytes > 6) {
      val ts = buf.readInt()
      val length = buf.readShort()
      function(Feedback(new String(buf.readBytes(length).array), new java.util.Date(ts * 1000)))
    }
  }

  override def channelInactive(ctx: ChannelHandlerContext) {
    info("APN Feedback disconnected!")
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    http.ExceptionHandler(ctx, cause).foreach(_.printStackTrace())
    ctx.close()
  }
}
