package io.wasted.util.test

import io.wasted.util.Tryo

import org.specs2.mutable._

class TryoSpec extends Specification {

  "Specification for Tryo Try/Failure/succes wrapper.".title

  val tryoSuccess = Tryo("success!")
  val tryoFailure = Tryo(throw new IllegalArgumentException)

  "Tryo(throw new IllegalArgumentException)" should {
    "have thrown an Exception and returned None" in { tryoFailure must beNone }
  }

  "Tryo(\"success!\")" should {
    "have also a result containing 'success!'" in { tryoSuccess must_== Some("success!") }
  }
}

