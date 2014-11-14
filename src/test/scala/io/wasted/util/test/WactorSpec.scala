package io.wasted.util.test

import io.wasted.util.Wactor
import org.scalatest._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.time.SpanSugar._

class WactorSpec extends WordSpec with AsyncAssertions with BeforeAndAfter {
  val w = new Waiter
  val testInt = 5
  val testString = "wohooo!"

  class TestWactor extends Wactor(5) {
    def receive = {
      case a: String =>
        assert(a == testString); w.dismiss()
      case a: Int => assert(a == testInt); w.dismiss()
    }

    override val loggerName = "TestWactor"
    override def exceptionCaught(e: Throwable) { e.printStackTrace() }
  }

  val actor = new TestWactor

  before(actor ! true) // wakeup

  "TestWactor" should {
    "have a message with \"" + testString + "\"" in {
      actor ! testString
      w.await(timeout(1 second))
    }

    "have a message with Integer " + testInt in {
      actor ! testInt
      w.await(timeout(1 second))
    }
  }

  after(actor ! Wactor.Die)
}

