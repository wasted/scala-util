package io.wasted.util.test

import java.net.InetSocketAddress

import com.twitter.conversions.time._
import com.twitter.util.Await
import io.netty.handler.codec.http.{ FullHttpRequest, FullHttpResponse, HttpResponseStatus }
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.SelfSignedCertificate
import io.netty.util.CharsetUtil
import io.wasted.util.http._
import org.scalatest._
import org.scalatest.concurrent._

class HttpsSpec extends WordSpec with ScalaFutures with AsyncAssertions with BeforeAndAfter {

  val responder = new HttpResponder("wasted-http")

  val cert = new SelfSignedCertificate()
  val sslCtx = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).build()
  val server1 = HttpServer().withSpecifics(HttpCodec[FullHttpRequest]().withTls(sslCtx)).handler {
    case (ctx, req) =>
      req.map { req =>
        val resp = if (req.getUri == "/bad_gw") HttpResponseStatus.BAD_GATEWAY else HttpResponseStatus.ACCEPTED
        responder(resp)
      }
  }.bind(new InetSocketAddress(8889))

  val client1 = HttpClient[FullHttpResponse]().withSpecifics(HttpCodec().withInsecureTls())
  val resp1: FullHttpResponse = Await.result(client1.get(new java.net.URI("https://anycast.io:443/")), 5.seconds)
  val resp2: FullHttpResponse = Await.result(client1.get(new java.net.URI("https://anycast.io:443/")), 5.seconds)

  "2 GET Request to https://anycast.io" should {
    "contain the phrase \"anycast\" somewhere" in {
      assert(resp1.content.toString(CharsetUtil.UTF_8).contains("anycast"))
      resp1.content.release()
    }
    "another phrase of \"anycast\" somewhere" in {
      assert(resp2.content.toString(CharsetUtil.UTF_8).contains("anycast"))
      resp2.content.release()
    }
  }

  val resp3: FullHttpResponse = Await.result(client1.get(new java.net.URI("https://localhost:8889/")), 5.seconds)
  val resp4: FullHttpResponse = Await.result(client1.get(new java.net.URI("https://localhost:8889/bad_gw")), 5.seconds)

  "GET Request to embedded Http Server" should {
    "returns status code ACCEPTED" in {
      assert(resp3.getStatus equals HttpResponseStatus.ACCEPTED)
      resp3.content.release()
    }
    "returns status code BAD_GATEWAY" in {
      assert(resp4.getStatus equals HttpResponseStatus.BAD_GATEWAY)
      resp4.content.release()
    }
  }

  server1.shutdown()
}

