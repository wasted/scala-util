package io.wasted.util.test

import io.wasted.util._

import org.specs2.mutable._

class ActorSpec extends Specification {

  "Specification for Wactor.".title

  var result1 = ""
  var result2 = 0

  class TestWactor extends Wactor(5) {
    def receive = {
      case x: String => result1 = x
      case x: Int => result2 = x
    }
    override val loggerName = "TestWactor"
    override def exceptionCaught(e: Throwable) { e.printStackTrace }
  }
  val actor = new TestWactor

  step(actor ! "wohooo!")
  step(actor ! 5)

  "TestWactor" should {
    "have set result1 to \"wohooo!\"" in {
      result1 must be_==("wohooo!").eventually
    }
    "have set result2 to \"5\"" in {
      result2 must be_==(5).eventually
    }
  }

  step(actor ! Wactor.Die)
}

