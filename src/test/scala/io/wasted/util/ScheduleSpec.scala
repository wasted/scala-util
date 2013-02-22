package io.wasted.util.test

import io.wasted.util._

import scala.concurrent.duration._
import org.specs2.mutable._

class ScheduleSpec extends Specification {

  "Specification for Schedule.".title

  var result1 = false
  var testfunc1 = () => result1 = true
  Schedule.once(testfunc1, DurationInt(5).millis)

  var result2 = false
  var result3 = false
  var testfunc2 = () => if (result2) result3 = true else result2 = true
  val cancelAgain = Schedule.again(testfunc2, DurationInt(5).millis, DurationInt(5).millis)

  "Schedule should have done 3 tests where results" should {
    "be true for Schedule.once" in { result1 must be_==(true).eventually }
    "be true for Schedule.again (first run)" in { result2 must be_==(true).eventually }
    "be true for Schedule.again (second run)" in { result3 must be_==(true).eventually }
  }

  step(cancelAgain.cancel)
}

