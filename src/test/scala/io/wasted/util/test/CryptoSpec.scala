package io.wasted.util.test

import io.wasted.util.{ Base64, Crypto, CryptoCipher }
import org.scalatest._

class CryptoSpec extends WordSpec {

  implicit val cipher = CryptoCipher("AES")

  val ourString = "this must work!!"
  val ourSalt = "1111111111111111"

  val encrypted: Array[Byte] = Crypto.encryptBinary(ourSalt, ourString)
  val base64Encoded: String = Base64.encodeString(encrypted)
  val base64Decoded: Array[Byte] = Base64.decodeBinary(base64Encoded)
  val theirString = Crypto.decryptString(ourSalt, Base64.decodeBinary(base64Encoded))

  "Pregenerated Base64 (" + ourString + ")" should {
    "be the same as the decrypted (" + theirString + ")" in {
      assert(ourString == theirString)
    }
  }

  "Encoded Array (" + encrypted.toList.toString + ")" should {
    "be the same as the decoded (" + base64Decoded.toList.toString + ")" in {
      assert(encrypted.deep == base64Decoded.deep)
    }
  }

}

