package io.wasted.util.test

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

import com.twitter.conversions.time._
import com.twitter.util.Await
import io.netty.handler.codec.http._
import io.netty.util.CharsetUtil
import io.wasted.util.http._
import org.scalatest._
import org.scalatest.concurrent._

class HttpSpec extends WordSpec with ScalaFutures with AsyncAssertions with BeforeAndAfter {

  val responder = new HttpResponder("wasted-http")
  var server = new AtomicReference[HttpServer[FullHttpRequest, HttpResponse]](null)

  before {
    server.set(HttpServer[FullHttpRequest, HttpResponse](NettyHttpCodec()).handler {
      case (ctx, req) =>
        req.map { req =>
          val resp = if (req.getUri == "/bad_gw") HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.ACCEPTED
          responder(resp)
        }
    }.bind(new InetSocketAddress(8888)))
  }

  val client1 = HttpClient(NettyHttpCodec[HttpRequest, FullHttpResponse]().withDecompression(false))

  "GET Request to http://wasted.io" should {
    "contain the phrase \"wasted\" somewhere" in {
      val resp1: FullHttpResponse = Await.result(client1.get(new java.net.URI("http://wasted.io/")), 5.seconds)
      assert(resp1.content.toString(CharsetUtil.UTF_8).contains("wasted"))
      resp1.content.release()
    }
  }

  val client2 = HttpClient(NettyHttpCodec[HttpRequest, FullHttpResponse]()).withTcpKeepAlive(true)

  "GET Request to embedded Http Server" should {
    "returns status code ACCEPTED" in {
      val resp2: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8888/")), 5.seconds)
      assert(resp2.getStatus equals HttpResponseStatus.ACCEPTED)
      resp2.content.release()
    }
    "returns status code BAD_GATEWAY" in {
      val resp3: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8888/bad_gw")), 5.seconds)
      assert(resp3.getStatus equals HttpResponseStatus.BAD_GATEWAY)
      resp3.content.release()
    }
  }

  after(server.get.shutdown())
}

