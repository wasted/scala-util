package io.wasted.util.test

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.twitter.conversions.time._
import com.twitter.util.Await
import io.netty.buffer.Unpooled
import io.netty.handler.codec.http._
import io.netty.handler.codec.http.websocketx.{ BinaryWebSocketFrame, TextWebSocketFrame }
import io.netty.util.CharsetUtil
import io.wasted.util.WheelTimer
import io.wasted.util.http._
import org.scalatest._
import org.scalatest.concurrent._

class WebSocketSpec extends WordSpec with ScalaFutures with AsyncAssertions with BeforeAndAfter {
  implicit val wheelTimer = WheelTimer

  val responder = new HttpResponder("wasted-ws")
  val socket1 = WebSocketHandler().onConnect { chan =>
    println("client connected")
  }.onDisconnect { chan =>
    println("client disconnected")
  }.handler {
    case (ctx, f) => println(f); Some(f.map(_.retain()))
  }.withHttpHandler {
    case (ctx, req) =>
      req.map { req =>
        val resp = if (req.getUri == "/bad_gw") HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.ACCEPTED
        responder(resp)
      }
  }
  var server = new AtomicReference[HttpServer[FullHttpRequest, HttpResponse]](null)

  before {
    server.set(HttpServer(NettyHttpCodec[FullHttpRequest, HttpResponse]())
      .handler(socket1.dispatch).bind(new InetSocketAddress(8886)))
  }

  var stringT = "worked"
  var string = ""
  val bytesT: Array[Byte] = stringT.getBytes(CharsetUtil.UTF_8)
  var bytes = ""

  val headers = Map(HttpHeaders.Names.UPGRADE -> HttpHeaders.Values.WEBSOCKET)

  "GET Request to embedded WebSocket Server" should {
    "open connection and send some data, close after" in {
      val client1 = Await.result(WebSocketClient().connectTo("ws://localhost:8886").handler {
        case (chan, fwsf) =>
          fwsf.map {
            case text: TextWebSocketFrame => string = text.text()
            case binary: BinaryWebSocketFrame => bytes = binary.content().toString(CharsetUtil.UTF_8)
            case x => println("got " + x)
          }.ensure(() => fwsf.map(_.release()))
      }.open(), 5.seconds)
      client1 ! new TextWebSocketFrame(stringT)
      client1 ! new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytesT).slice())
      Thread.sleep(500)
      client1.disconnect()
    }
    "returns the same string as sent" in {
      assert(string equals stringT)
    }
    "returns the same string as sent in bytes" in {
      assert(bytes equals stringT)
    }
  }

  val client2 = HttpClient[FullHttpResponse]().withSpecifics(NettyHttpCodec()).withTcpKeepAlive(true)

  "GET Request to embedded Http Server" should {
    "returns status code ACCEPTED" in {
      val resp2: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8886/")), 5.seconds)
      assert(resp2.getStatus equals HttpResponseStatus.ACCEPTED)
      resp2.content.release()
    }
    "returns status code BAD_GATEWAY" in {
      val resp3: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8886/bad_gw")), 5.seconds)
      assert(resp3.getStatus equals HttpResponseStatus.BAD_GATEWAY)
      resp3.content.release()
    }
  }

  after {
    server.get.shutdown()
  }
}

