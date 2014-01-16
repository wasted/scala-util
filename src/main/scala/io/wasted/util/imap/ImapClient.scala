package io.wasted.util.imap

import io.wasted.util._
import io.wasted.util.http.ExceptionHandler
import scala.concurrent.{Promise, Future}
import scala.concurrent.duration._
import io.netty.channel._
import io.netty.bootstrap.Bootstrap
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.handler.codec.{Delimiters, DelimiterBasedFrameDecoder}
import io.netty.handler.codec.string._
import io.netty.handler.ssl.SslHandler
import java.net.InetSocketAddress

/**
 * IMAP Authentication Type
 */
object Auth extends Enumeration {
  val PLAIN, SSL = Value
}

/**
 * Configuration Parameters for the IMAP Client
 * @param host Host to connect to
 * @param port Port to connect to
 * @param authType Authentication Type
 * @param username Username
 * @param password Password
 * @param timeout Duration for actions
 */
case class Config(host: String, port: Int, authType: Auth.Value, username: String, password: String, timeout: Duration)

/**
 * IMAP Client object
 * @param config Configuration
 */
class ImapClient(config: Config) extends Logger {
  private var channel: Option[Channel] = None
  private val lru = new LruMap[String, Future[String]](20, None, None)

  private val srv = new Bootstrap()
  private val bootstrap = srv.group(Netty.eventLoop)
    .channel(classOf[NioSocketChannel])
    .option[java.lang.Boolean](ChannelOption.TCP_NODELAY, true)
    .option[java.lang.Boolean](ChannelOption.SO_KEEPALIVE, true)
    .option[java.lang.Boolean](ChannelOption.SO_REUSEADDR, true)
    .option[java.lang.Integer](ChannelOption.SO_LINGER, 0)
    .option[java.lang.Integer](ChannelOption.CONNECT_TIMEOUT_MILLIS, config.timeout.toMillis.toInt)
    .handler(new ChannelInitializer[SocketChannel] {
      override def initChannel(ch: SocketChannel) {
        val p = ch.pipeline()
        if (config.authType == Auth.SSL) {
          val sslEngine = ssl.Ssl.client(config.host, config.port).copy(handlesRenegotiation = true)
          p.addLast("ssl", new SslHandler(sslEngine.self))
        }
        p.addLast("framer", new DelimiterBasedFrameDecoder(8192, true, Delimiters.lineDelimiter()))
        p.addLast("decoder", new StringDecoder())
        p.addLast("encoder", new StringEncoder())
        p.addLast("imap", new ImapResponseAdapter(this))
      }
    })

  def connect(): Future[Channel] = {
    val promise = Promise[Channel]()
    val cf = bootstrap.connect(new InetSocketAddress(config.host, config.port))
    cf.addListener(new ChannelFutureListener {
      def operationComplete(future: ChannelFuture) {
        channel = Some(future.channel())
        promise.success(future.channel())
      }

    })
    promise.future
  }

  def reconnect(): Unit = synchronized {
    disconnect()
    connect().wait(5000)
  }

  def disconnect(): Unit = for {
    chan <- channel
  } chan.close()

  private var _capabilities = List[String]()
  def capabilities = _capabilities

  def folders(): Future[List[String]]

}

/**
 * Netty Response Adapter which is used for IMAP.
 */
@ChannelHandler.Sharable
class ImapResponseAdapter(client: ImapClient) extends SimpleChannelInboundHandler[String]() {
  override def channelInactive(ctx: ChannelHandlerContext) {
    client.info("disconnected!")
    client.reconnect()
  }

  override def channelActive(ctx: ChannelHandlerContext) {
    client.info("connected!")
    client.reconnect()
  }

  final val capsPrefix = "\\* OK \\[CAPAPABILITY (.*)\\]".r
  override def channelRead0(ctx: ChannelHandlerContext, msg: String) {
    val ch = ctx.channel()

    if (msg.startsWith("* OK [CAPABILITY ")) {

    }
    msg match {
      case capsPrefix(caps) =>
        client._capabilities = caps.
    }


  }
  override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
    val message = e.getMessage.toString
    log.debug("Message received [{}] from {}", e.getMessage, e.getRemoteAddress)
    if (message.startsWith(CAPABILITY_PREFIX)) {
      log.debug("Capabilities received {}", message)
      this.capabilities = Arrays.asList(message.substring(CAPABILITY_PREFIX.length + 1).split("[ ]+"):_*)
      loginComplete.countDown()
      return
    }
    if (!isLoggedIn) {
      if (message.matches("[.] OK .*@.* \\(Success\\)")) {
        log.debug("Authentication success.")
        isLoggedIn = true
        loginComplete.countDown()
      }
      return
    }
    complete(message)
  }

  private def complete(message: String) {
    val command = Command.response(message)
    if (command == null) {
      log.error("Could not find the command that the received message was a response to {}", message)
      return
    }
    val completion = completions.remove(command)
    if (completion == null) {
      log.error("Could not find the completion for command {} (Was it ever issued?)", command)
      return
    }
    completion.complete(message)
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    ExceptionHandler(ctx, cause) match {
      case Some(e) =>
      case _ => client.reconnect()
    }
    ctx.close()
  }
}

