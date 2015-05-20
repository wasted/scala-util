package io.wasted.util.test

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

import com.twitter.util.{ Duration, Future }
import io.netty.handler.codec.http.{ FullHttpRequest, FullHttpResponse, HttpResponse, HttpResponseStatus }
import io.wasted.util.http._
import org.scalatest._
import org.scalatest.concurrent._
import org.scalatest.time.Span

class HttpRetrySpec extends FunSuite with ShouldMatchers with AsyncAssertions with BeforeAndAfter {

  val responder = new HttpResponder("wasted-http")
  val retries = 2
  val counter = new AtomicInteger()
  var server = new AtomicReference[HttpServer[FullHttpRequest, HttpResponse]](null)

  before {
    server.set(HttpServer[FullHttpRequest, HttpResponse](NettyHttpCodec()).handler {
      case (ctx, req) =>
        req.map { req =>
          if (req.getUri == "/sleep") Thread.sleep(50)
          if (req.getUri == "/retry") {
            val reqCount = counter.incrementAndGet()
            println("at " + reqCount)
            if (reqCount <= retries) Thread.sleep(50)
          }
          responder(HttpResponseStatus.OK)
        }
    }.bind(new InetSocketAddress(8887)))
  }

  val client1 = HttpClient[FullHttpResponse]().withSpecifics(NettyHttpCodec()).withTcpKeepAlive(true)
  test("Failing Timeout") {
    // warmup request
    client1.get(new java.net.URI("http://localhost:8887/warmup")).map(_.release())

    val w = new Waiter // Do this in the main test thread
    client1.withGlobalTimeout(Duration(20, TimeUnit.MILLISECONDS))
      .get(new java.net.URI("http://localhost:8887/sleep")).rescue {
        case t =>
          w { () }
          w.dismiss()
          Future.value(null)
      }
    w.await()
  }

  test("Working Timeout") {
    val w = new Waiter // Do this in the main test thread
    client1.withGlobalTimeout(Duration(90, TimeUnit.MILLISECONDS))
      .get(new java.net.URI("http://localhost:8887/sleep")).map { resp =>
        w {
          resp.getStatus.code() should equal(200)
        }
        resp.release()
        w.dismiss()
      }
    w.await()
  }

  test("Retry") {
    val w = new Waiter // Do this in the main test thread
    client1.withRetries(retries)
      .withRequestTimeout(Duration(49, TimeUnit.MILLISECONDS))
      .withGlobalTimeout(Duration(190, TimeUnit.MILLISECONDS))
      .get(new java.net.URI("http://localhost:8887/retry")).map { resp =>
        w {
          resp.getStatus.code() should equal(200)
        }
        resp.release()
        w.dismiss()
      }
    w.await(timeout(Span(600, org.scalatest.time.Millis)))
  }

  after(server.get.shutdown())
}

