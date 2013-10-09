package io.wasted.util

import java.security.MessageDigest
import java.io.FileInputStream
import javax.crypto.spec.SecretKeySpec

case class HashingAlgo(name: String = "HmacSHA256")
case class HexingAlgo(name: String = "SHA")

/**
 * Helper Object for hashing different Strings and Files.
 */
object Hashing {

  /**
   * Sign the payload with the given key using the given algorithm.
   *
   * @param key Key used for hashing
   * @param payload Big mystery here..
   * @param alg Algorithm to be used. Possible choices are HmacMD5, HmacSHA1, HmacSHA256, HmacSHA384 and HmacSHA512.
   */
  def sign(key: String, payload: String)(implicit alg: HashingAlgo) = {
    val mac = javax.crypto.Mac.getInstance(alg.name)
    val secret = new SecretKeySpec(key.toCharArray.map(_.toByte), alg.name)
    mac.init(secret)
    // catch (InvalidKeyException e)
    mac.doFinal(payload.toCharArray.map(_.toByte)).map(b => Integer.toString((b & 0xff) + 0x100, 16).substring(1)).mkString
  }

  /**
   * Create an hex encoded SHA hash from a Byte array using the given algorithm.
   *
   * @param in ByteArray to be encoded
   * @param alg Algorithm to be used. Possible choices are HmacMD5, HmacSHA1, HmacSHA256, HmacSHA384 and HmacSHA512.
   */
  def hexDigest(in: Array[Byte])(implicit alg: HexingAlgo): String = {
    val binHash = MessageDigest.getInstance(alg.name).digest(in)
    hexEncode(binHash)
  }

  /**
   * Create an hex encoded SHA hash from a File on disk using the given algorithm.
   *
   * @param file Path to the file
   * @param alg Algorithm to be used. Possible choices are HmacMD5, HmacSHA1, HmacSHA256, HmacSHA384 and HmacSHA512.
   */
  def hexFileDigest(file: String)(implicit alg: HexingAlgo): String = {
    val md = MessageDigest.getInstance(alg.name)
    val input = new FileInputStream(file)
    val buffer = new Array[Byte](1024)
    Stream.continually(input.read(buffer)).takeWhile(_ != -1).foreach(md.update(buffer, 0, _))
    hexEncode(md.digest)
  }

  /**
   * Encode a ByteArray as hexadecimal characters.
   *
   * @param in ByteArray to be encoded
   */
  def hexEncode(in: Array[Byte]): String = {
    val sb = new StringBuilder
    val len = in.length
    def addDigit(in: Array[Byte], pos: Int, len: Int, sb: StringBuilder) {
      if (pos < len) {
        val b: Int = in(pos)
        val msb = (b & 0xf0) >> 4
        val lsb = b & 0x0f
        sb.append(if (msb < 10) ('0' + msb).asInstanceOf[Char] else ('a' + (msb - 10)).asInstanceOf[Char])
        sb.append(if (lsb < 10) ('0' + lsb).asInstanceOf[Char] else ('a' + (lsb - 10)).asInstanceOf[Char])

        addDigit(in, pos + 1, len, sb)
      }
    }
    addDigit(in, 0, len, sb)
    sb.toString()
  }

  /**
   * Decode a ByteArray from hex.
   *
   * @param in String to be decoded
   */
  def hexDecode(str: String): Array[Byte] = {
    val max = str.length / 2
    val ret = new Array[Byte](max)
    var pos = 0

    def byteOf(in: Char): Int = in match {
      case '0' => 0
      case '1' => 1
      case '2' => 2
      case '3' => 3
      case '4' => 4
      case '5' => 5
      case '6' => 6
      case '7' => 7
      case '8' => 8
      case '9' => 9
      case 'a' | 'A' => 10
      case 'b' | 'B' => 11
      case 'c' | 'C' => 12
      case 'd' | 'D' => 13
      case 'e' | 'E' => 14
      case 'f' | 'F' => 15
      case _ => 0
    }

    while (pos < max) {
      val two = pos * 2
      val ch: Char = str.charAt(two)
      val cl: Char = str.charAt(two + 1)
      ret(pos) = (byteOf(ch) * 16 + byteOf(cl)).toByte
      pos += 1
    }

    ret
  }
}
