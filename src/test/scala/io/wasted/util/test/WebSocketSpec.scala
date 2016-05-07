package io.wasted.util.test

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.twitter.conversions.time._
import com.twitter.util.{ Await, Promise }
import io.netty.buffer.{ ByteBufHolder, Unpooled }
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.{ BinaryWebSocketFrame, TextWebSocketFrame }
import io.netty.util.{ CharsetUtil, ReferenceCountUtil }
import io.wasted.util.{ Logger, WheelTimer }
import io.wasted.util.http._
import org.scalatest._
import org.scalatest.concurrent._

class WebSocketSpec extends WordSpec with ScalaFutures with AsyncAssertions with BeforeAndAfter with Logger {
  implicit val wheelTimer = WheelTimer

  val responder = new HttpResponder("wasted-ws")
  var server = new AtomicReference[HttpServer[FullHttpRequest, HttpResponse]](null)

  before {
    val socket1 = WebSocketHandler().onConnect { chan =>
      info("client connected")
    }.onDisconnect { chan =>
      info("client disconnected")
    }.handler {
      case (ctx, f) => println(f); Some(f.map(_.retain()))
    }.withHttpHandler {
      case (ctx, req) =>
        req.map { req =>
          val resp = if (req.uri == "/bad_gw") HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.ACCEPTED
          responder(resp)
        }
    }
    server.set(HttpServer(NettyHttpCodec[FullHttpRequest, HttpResponse]())
      .handler(socket1.dispatch).bind(new InetSocketAddress(8890)))
  }

  val stringT = "worked"
  val string = new Promise[String]
  val bytesT: Array[Byte] = stringT.getBytes(CharsetUtil.UTF_8)
  val bytes = new Promise[String]

  val uri = new java.net.URI("http://localhost:8890/")

  "GET Request to embedded WebSocket Server" should {
    "open connection and send some data, close after" in {
      val client1 = Await.result(WebSocketClient().connectTo("127.0.0.1", 8890).open(uri, uri), 5.seconds)
      client1.foreach {
        case text: TextWebSocketFrame =>
          string.setValue(text.text())
          text.release()
        case binary: BinaryWebSocketFrame =>
          bytes.setValue(binary.content().toString(CharsetUtil.UTF_8))
          binary.release()
        case x =>
          ReferenceCountUtil.release(x)
          error("got " + x)
      }
      client1 ! new TextWebSocketFrame(stringT)
      client1 ! new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytesT).slice())
      Thread.sleep(5000)
    }
    "returns the same string as sent" in {
      assert(Await.result(string, 2.seconds) equals stringT)
    }
    "returns the same string as sent in bytes" in {
      assert(Await.result(bytes, 2.seconds) equals stringT)
    }
  }

  val client2 = HttpClient[FullHttpResponse]().withSpecifics(NettyHttpCodec()).withTcpKeepAlive(true)

  "GET Request to embedded Http Server" should {
    "returns status code ACCEPTED" in {
      val resp2: FullHttpResponse = Await.result(client2.get(uri), 5.seconds)
      assert(resp2.status() equals HttpResponseStatus.ACCEPTED)
      resp2.content.release()
    }
    "returns status code BAD_GATEWAY" in {
      val resp3: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8890/bad_gw")), 5.seconds)
      assert(resp3.status() equals HttpResponseStatus.BAD_GATEWAY)
      resp3.content.release()
    }
  }

  after {
    server.get.shutdown()
  }
}

