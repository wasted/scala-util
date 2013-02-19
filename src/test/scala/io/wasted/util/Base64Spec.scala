package io.wasted.util.test

import io.wasted.util._

import org.specs2.mutable._

class Base64Spec extends Specification {

  "Specification for Base64 functions.".title

  val ourString = "it works!"
  val ourB64 = "aXQgd29ya3Mh"
  val ourB64Array = Array(97, 88, 81, 103, 100, 50, 57, 121, 97, 51, 77, 104)

  val theirB64 = Base64.encodeString(ourString)
  val theirB64Array = Base64.encodeBinary(ourString)
  val theirB64String = Base64.decodeString(theirB64Array)

  "Precalculated Base64 String (" + ourB64 + ")" should {
    "be the same as the calculated (" + theirB64 + ")" in {
      ourB64 must_== theirB64
    }
  }

  "Precalculated sign (" + ourB64Array + ")" should {
    "be the same as the calculated (" + theirB64Array + ")" in {
      ourB64Array must_== theirB64Array
    }
  }

  "Pre-set string of \"" + ourString + "\"" should {
    "be the same as the calculated (" + theirB64String + ")" in {
      ourString must_== theirB64String
    }
  }

}

