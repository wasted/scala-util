package io.wasted.util

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher

case class CryptoCipher(name: String = "AES", jce: Boolean = true)

/**
 * Helper methods for en-/decrypting strings.
 */
object Crypto {

  /**
   * Encrypt the given String using the given Cipher and the supplied salt.
   * @param salt Secret to encrypt with
   * @param payload Payload to be encrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding..
   * @return Byte-Array of the encrypted data
   */
  def encryptBinary(salt: String, payload: String)(implicit cipher: CryptoCipher): Array[Byte] =
    encryptBinary(salt, payload.getBytes("UTF-8"))(cipher)

  /**
   * Encrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to encrypt with
   * @param payload Payload to be encrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return Byte-Array of the encrypted data
   */
  def encryptBinary(salt: String, payload: Array[Byte])(implicit cipher: CryptoCipher): Array[Byte] = {
    val key = new SecretKeySpec(salt.getBytes("UTF-8"), "AES")
    val cipherI = if (cipher.jce) Cipher.getInstance(cipher.name, "SunJCE") else Cipher.getInstance(cipher.name)
    cipherI.init(Cipher.ENCRYPT_MODE, key)
    cipherI.doFinal(payload)
  }

  /**
   * Encrypt the given String using the given Cipher and the supplied salt.
   * @param salt Secret to encrypt with
   * @param payload Payload to be encrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return Base64-String of the encrypted data
   */
  def encryptString(salt: String, payload: String)(implicit cipher: CryptoCipher): String =
    new String(encryptBinary(salt, payload.getBytes("UTF-8"))(cipher), "UTF-8")

  /**
   * Encrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to encrypt with
   * @param payload Payload to be encrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return Base64-String of the encrypted data
   */
  def encryptString(salt: String, payload: Array[Byte])(implicit cipher: CryptoCipher): String =
    new String(encryptBinary(salt, payload)(cipher), "UTF-8")

  /**
   * Decrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to decrypt with
   * @param payload Payload to be decrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return Byte-Array of the decrypted data
   */
  def decryptBinary(salt: String, payload: Array[Byte])(implicit cipher: CryptoCipher): Array[Byte] = {
    val key = new SecretKeySpec(salt.getBytes("UTF-8"), cipher.name)
    val cipherI = if (cipher.jce) Cipher.getInstance(cipher.name, "SunJCE") else Cipher.getInstance(cipher.name)
    cipherI.init(Cipher.DECRYPT_MODE, key)
    cipherI.doFinal(payload)
  }

  /**
   * Decrypt the given String using the given Cipher and the supplied salt.
   * @param salt Secret to decrypt with
   * @param payload Payload to be decrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return Byte-Array of the decrypted data
   */
  def decryptBinary(salt: String, payload: String)(implicit cipher: CryptoCipher): Array[Byte] = {
    decryptBinary(salt, payload)(cipher)
  }

  /**
   * Decrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to decrypt with
   * @param payload Payload to be decrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return String of the decrypted data
   */
  def decryptString(salt: String, payload: Array[Byte])(implicit cipher: CryptoCipher): String = {
    new String(decryptBinary(salt, payload)(cipher), "UTF-8")
  }

  /**
   * Decrypt the given String using the given Cipher and the supplied salt.
   * @param salt Secret to decrypt with
   * @param payload Payload to be decrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding.
   * @return String of the decrypted data
   */
  def decryptString(salt: String, payload: String)(implicit cipher: CryptoCipher): String = {
    decryptString(salt, payload.getBytes("UTF-8"))(cipher)
  }
}
