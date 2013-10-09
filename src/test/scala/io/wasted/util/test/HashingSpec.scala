package io.wasted.util.test

import io.wasted.util.{ HexingAlgo, HashingAlgo, Hashing }

import org.specs2.mutable._

class HashingSpec extends Specification {

  "Specification for Hashing functions.".title

  implicit val hashingAlgo = HashingAlgo("HmacSHA256")
  implicit val hexingAlgo = HexingAlgo("SHA")

  val ourString = "this must work!!"
  val ourHexDigest = "c2bf26e94cab462fa275a3dc41f04cf3e67d470a"
  val ourSignature = "6efac23cabff39ec218e18a7a2494591095e74913ada965fbf8ad9d9b9f38d91"
  val ourHexSignature = "this works?!"

  val theirHexDigest = Hashing.hexDigest(ourString.getBytes("UTF-8"))
  val theirSignature = Hashing.sign(ourString, theirHexDigest)
  val theirHexSignature = Hashing.hexEncode(ourHexSignature.getBytes("UTF-8"))

  "Precalculated hex-digest (" + ourHexDigest + ")" should {
    "be the same as the calculated (" + theirHexDigest + ")" in {
      ourHexDigest must_== theirHexDigest
    }
  }

  "Precalculated hex-encoded (" + ourHexSignature + ")" should {
    "be the same as the calculated (" + theirHexSignature + ")" in {
      ourHexSignature must_== new String(Hashing.hexDecode(theirHexSignature), "UTF-8")
    }
  }

  "Precalculated sign (" + ourSignature + ")" should {
    "be the same as the calculated (" + theirSignature + ")" in {
      ourSignature must_== theirSignature
    }
  }
}

