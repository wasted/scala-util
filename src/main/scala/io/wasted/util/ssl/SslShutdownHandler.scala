package io.wasted.util.ssl

import io.wasted.util.{ Logger, Tryo }

import java.lang.reflect.Method
import javax.net.ssl.SSLException

import io.netty.channel._

class SslShutdownHandler(o: Object) extends ChannelInboundHandlerAdapter with Logger {
  private[this] val shutdownMethod: Option[Method] =
    try {
      Some(o.getClass().getMethod("shutdown"))
    } catch {
      case _: NoSuchMethodException => None
    }

  private[this] def shutdownAfterChannelClosure() {
    shutdownMethod foreach { method: Method =>
      method.invoke(o)
    }
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    // remove the ssl handler so that it doesn't trap the disconnect
    if (cause.isInstanceOf[SSLException]) ctx.pipeline.remove("ssl")
    super.exceptionCaught(ctx, cause)
  }

  override def channelUnregistered(ctx: ChannelHandlerContext) {
    shutdownAfterChannelClosure()

    super.channelUnregistered(ctx)
  }
}

