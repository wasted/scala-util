package io.wasted.util

object Base64 {
  /** Encodes the given String into a Base64 String. **/
  def encodeString(in: String): String = java.util.Base64.getUrlEncoder.encodeToString(in.getBytes("UTF-8"))

  /** Encodes the given ByteArray into a Base64 String. **/
  def encodeString(in: Array[Byte]): String = java.util.Base64.getUrlEncoder.encodeToString(in)

  /** Encodes the given String into a Base64 ByteArray. **/
  def encodeBinary(in: String): Array[Byte] = java.util.Base64.getUrlEncoder.encode(in.getBytes("UTF-8"))

  /** Encodes the given ByteArray into a Base64 ByteArray. **/
  def encodeBinary(in: Array[Byte]): Array[Byte] = java.util.Base64.getUrlEncoder.encode(in)

  /** Decodes the given Base64-ByteArray into a String. **/
  def decodeString(in: Array[Byte]): String = new String(java.util.Base64.getUrlDecoder.decode(in), "UTF-8")

  /** Decodes the given Base64-String into a String. **/
  def decodeString(in: String): String = new String(java.util.Base64.getUrlDecoder.decode(in), "UTF-8")

  /** Decodes the given Base64-String into a ByteArray. **/
  def decodeBinary(in: String): Array[Byte] = java.util.Base64.getUrlDecoder.decode(in)

  /** Decodes the given Base64-ByteArray into a ByteArray. **/
  def decodeBinary(in: Array[Byte]): Array[Byte] = java.util.Base64.getUrlDecoder.decode(in)
}
