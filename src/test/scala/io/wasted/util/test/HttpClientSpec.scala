package io.wasted.util.test

import io.wasted.util.http.HttpClient
import io.wasted.util.Logger

import org.specs2.mutable._

import io.netty.handler.codec.http.FullHttpResponse
import io.netty.util.CharsetUtil
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Success

class HttpClientSpec extends Specification with Logger {

  "HttpClient".title

  val url = new java.net.URL("http://wasted.io/")

  var result1 = false
  var content1 = "failed"

  val client1 = HttpClient(false, 5, None)
  val future = client1.get(url)
  future onComplete {
    case Success(resp: FullHttpResponse) =>
      result1 = true
      content1 = resp.content.toString(CharsetUtil.UTF_8)
      resp.content.release()
    case _ =>
  }

  "GET Request to http://wasted.io" should {
    "return true" in {
      result1 must be_==(true).eventually
    }
    "contain the phrase \"wasted\" somewhere" in {
      content1 must contain("wasted").eventually
    }
  }

  step(client1.shutdown())
}

