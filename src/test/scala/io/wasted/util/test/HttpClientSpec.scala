package io.wasted.util.test

import java.net.InetSocketAddress

import com.twitter.conversions.time._
import com.twitter.util.Await
import io.netty.handler.codec.http.{ FullHttpRequest, FullHttpResponse, HttpResponseStatus }
import io.netty.util.CharsetUtil
import io.wasted.util.http._
import org.scalatest._
import org.scalatest.concurrent._

class HttpClientSpec extends WordSpec with ScalaFutures with AsyncAssertions with BeforeAndAfter {

  val responder = new HttpResponder("wasted-http")
  val server1 = HttpServer().withSpecifics(HttpCodec[FullHttpRequest]()).handler {
    case (ctx, req) =>
      req.map { req =>
        val resp = if (req.getUri == "/bad_gw") HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.ACCEPTED
        responder(resp)
      }
  }.bind(new InetSocketAddress(8888))

  val client1 = HttpClient[FullHttpResponse]().withSpecifics(HttpCodec().withDecompression(false))
  val resp1: FullHttpResponse = Await.result(client1.get(new java.net.URI("http://wasted.io/")), 5.seconds)

  "GET Request to http://wasted.io" should {
    "contain the phrase \"wasted\" somewhere" in {
      assert(resp1.content.toString(CharsetUtil.UTF_8).contains("wasted"))
      resp1.content.release()
    }
  }

  val client2 = HttpClient[FullHttpResponse]().withSpecifics(HttpCodec()).withTcpKeepAlive(true)
  val resp2: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8888/")), 5.seconds)
  val resp3: FullHttpResponse = Await.result(client2.get(new java.net.URI("http://localhost:8888/bad_gw")), 5.seconds)

  "GET Request to embedded Http Server" should {
    "returns status code ACCEPTED" in {
      assert(resp2.getStatus equals HttpResponseStatus.ACCEPTED)
      resp2.content.release()
    }
    "returns status code BAD_GATEWAY" in {
      assert(resp3.getStatus equals HttpResponseStatus.BAD_GATEWAY)
      resp3.content.release()
    }
  }

  server1.shutdown()
}

