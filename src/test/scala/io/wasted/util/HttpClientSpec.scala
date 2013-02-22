package io.wasted.util.test

import io.wasted.util.http._

import io.netty.handler.codec.http._
import io.netty.handler.codec.http.HttpResponseStatus._
import org.specs2.mutable._
import scala.collection.JavaConverters._

class HttpClientSpec extends Specification {

  "Specification for HttpClient.".title

  val url = new java.net.URL("http://wasted.io/")

  var result1 = false
  var content1 = "failed"

  def client1func(x: Option[HttpResponse]): Unit = x match {
    case Some(rsp) if rsp.getStatus == OK =>
      result1 = true
      content1 = rsp.toString()
    case x: Object =>
  }

  val client1 = HttpClient(client1func _, false, 5, None)
  step(client1.get(url))

  "GET Request to http://wasted.io" should {
    "return true" in {
      result1 must be_==(true).eventually
    }
    "contain the phrase \"wasted.io\" somewhere" in {
      content1 must contain("wasted.io").eventually
    }
  }

  step(client1.shutdown)
}

