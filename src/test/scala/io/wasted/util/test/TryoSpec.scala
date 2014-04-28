package io.wasted.util.test

import io.wasted.util.Tryo

import org.scalatest._

class TryoSpec extends WordSpec {
  val tryoSuccess = Tryo("success!")
  val tryoFailure = Tryo(throw new IllegalArgumentException)

  "Tryo(throw new IllegalArgumentException)" should {
    "have thrown an Exception and returned None" in { assert(tryoFailure.isEmpty) }
  }

  "Tryo(\"success!\")" should {
    "have also a result containing 'success!'" in { assert(tryoSuccess == Some("success!")) }
  }
}

