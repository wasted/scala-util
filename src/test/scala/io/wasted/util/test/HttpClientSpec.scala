package io.wasted.util.test

import io.wasted.util.http.HttpClient
import io.wasted.util.Logger

import org.specs2.mutable._

import io.netty.handler.codec.http.{ HttpObject, FullHttpResponse }
import io.netty.handler.codec.http.HttpResponseStatus.{ OK, MOVED_PERMANENTLY }
import io.netty.util.CharsetUtil

class HttpClientSpec extends Specification with Logger {

  "Specification for HttpClient.".title

  val url = new java.net.URL("http://wasted.io/")

  var result1 = false
  var content1 = "failed"

  def client1func(x: Option[HttpObject]): Unit = x match {
    case Some(rsp: FullHttpResponse) if rsp.getStatus == OK || rsp.getStatus == MOVED_PERMANENTLY =>
      result1 = true
      content1 = rsp.content().toString(CharsetUtil.UTF_8)
    case x: Object =>
  }

  val client1 = HttpClient(client1func _, false, 5, None)
  step(client1.get(url))

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

