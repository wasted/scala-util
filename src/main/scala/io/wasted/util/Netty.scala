package io.wasted.util

import io.netty.channel.nio.NioEventLoopGroup

private[util] object Netty {
  val eventLoop = new NioEventLoopGroup
}
