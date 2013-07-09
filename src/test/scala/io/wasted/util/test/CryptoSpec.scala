package io.wasted.util.test

import io.wasted.util.{ Base64, Crypto }

import org.specs2.mutable._

class CryptoSpec extends Specification {

  "Specification for Crypto functions.".title

  val ourString = "this must work!!"
  val ourSalt = "1111111111111111"
  val ourEncryption = Array[Byte](56, -75, -77, -114, 46, -127, 123, -95, 52, -7, -120, -26, 72, 35, -55, -122, -13, 12, 105, -60, -7, 69, -26, 84, -21, -44, -77, -120, -79, -56, -9, -112)
  val ourEncryptionString = Base64.encodeString(new String(ourEncryption))

  val theirDecryptionString = Base64.decodeString(ourEncryptionString)
  val theirDecryption = Crypto.encrypt(ourSalt, ourString)
  val theirString = new String(Crypto.decrypt(ourSalt, theirDecryption))

  "Pregenerated string (" + ourString + ")" should {
    "be the same as the decrypted (" + theirString + ")" in {
      ourString must_== theirString
    }
  }

  "Precalculated encrypted bytes (" + ourEncryption.toList.toString + ")" should {
    "be the same as the calculated (" + theirDecryption.toList.toString + ")" in {
      ourEncryption must_== theirDecryption
    }
  }

}

