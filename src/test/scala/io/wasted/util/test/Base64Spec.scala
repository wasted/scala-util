package io.wasted.util.test

import io.wasted.util.Base64

import org.scalatest._

class Base64Spec extends WordSpec {
  val ourString = "it works!"
  val ourB64 = "aXQgd29ya3Mh"
  val ourB64Array = Array(97, 88, 81, 103, 100, 50, 57, 121, 97, 51, 77, 104).map(_.toByte)

  val theirB64 = Base64.encodeString(ourString)
  val theirB64Array = Base64.encodeBinary(ourString)
  val theirB64String = Base64.decodeString(theirB64Array)

  "Precalculated Base64 String (" + ourB64 + ")" should {
    "be the same as the calculated (" + theirB64 + ")" in {
      assert(ourB64 == theirB64)
    }
  }

  "Precalculated Base64 Array (" + ourB64Array.mkString(", ") + ")" should {
    "be the same as the calculated (" + theirB64Array.mkString(", ") + ")" in {
      assert(ourB64Array.deep == theirB64Array.deep)
    }
  }

  "Pre-set string of \"" + ourString + "\"" should {
    "be the same as the calculated (" + theirB64String + ")" in {
      assert(ourString == theirB64String)
    }
  }

}

