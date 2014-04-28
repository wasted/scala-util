package io.wasted.util.test

import io.wasted.util.http.HttpClient

import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.time.SpanSugar._

import io.netty.util.CharsetUtil
import scala.concurrent.Await

class HttpClientSpec extends WordSpec with ScalaFutures with AsyncAssertions with BeforeAndAfter {
  val url = new java.net.URL("http://wasted.io/")

  val client1 = HttpClient(false, 5, None)
  val resp = Await.result(client1.get(url), 5.seconds)

  "GET Request to http://wasted.io" should {
    "contain the phrase \"wasted\" somewhere" in {
      assert(resp.content.toString(CharsetUtil.UTF_8).contains("wasted"))
      resp.content.release()
    }
  }

  after(client1.shutdown())
}

