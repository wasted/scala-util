package io.wasted.util.test

import io.wasted.util.{Schedule, WheelTimer}
import org.scalatest._
import org.scalatest.concurrent.AsyncAssertions
import org.scalatest.time.SpanSugar._

class ScheduleSpec extends WordSpec with AsyncAssertions {
  val w = new Waiter
  implicit val wheel = WheelTimer

  var result2 = false
  var result3 = false
  var testfunc2 = () => if (result2) {
    result3 = true
    w.dismiss()
  } else {
    result2 = true
    w.dismiss()
  }
  val cancelAgain = Schedule.again(testfunc2, scala.concurrent.duration.DurationInt(5).millis, scala.concurrent.duration.DurationInt(5).millis)

  "Schedule should have done 3 tests where results" should {
    "be true for Schedule.once" in {
      Schedule.once(() => w.dismiss(), scala.concurrent.duration.DurationInt(5).millis)
      w.await(timeout(500 millis), dismissals(2))
    }
    "be true for Schedule.again (first run)" in {
      Schedule.once(testfunc2, scala.concurrent.duration.DurationInt(5).millis)
      w.await(timeout(500 millis), dismissals(2))
    }
    "be true for Schedule.again (second run)" in {
      Schedule.once(testfunc2, scala.concurrent.duration.DurationInt(5).millis)
      w.await(timeout(500 millis), dismissals(2))
    }
  }
}

