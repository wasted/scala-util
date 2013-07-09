package io.wasted.util

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher

/**
 * Helper methods for en-/decrypting strings.
 */
object Crypto {

  /**
   * Encrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to encrypt with
   * @param payload Payload to be encrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding. Defaults to AES.
   * @return Byte-Array of the encrypted data
   */
  def encrypt(salt: String, payload: String, cipher: String = "AES"): Array[Byte] = encrypt(salt, payload.toCharArray.map(_.toByte), cipher)

  /**
   * Encrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to encrypt with
   * @param payload Payload to be encrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding. Defaults to AES.
   * @return Byte-Array of the encrypted data
   */
  def encrypt(salt: String, payload: Array[Byte], cipher: String): Array[Byte] = {
    val key = new SecretKeySpec(salt.getBytes, "AES")
    val cipherI = Cipher.getInstance(cipher, "SunJCE")
    cipherI.init(Cipher.ENCRYPT_MODE, key)
    cipherI.doFinal(payload)
  }

  /**
   * Decrypt the given Payload using the given Cipher and the supplied salt.
   * @param salt Secret to decrypt with
   * @param payload Payload to be decrypted
   * @param cipher Cipher to be used. Possible choices are AES, ECB, PKCS5Padding. Defaults to AES.
   * @return Byte-Array of the decrypted data
   */
  def decrypt(salt: String, payload: Array[Byte], cipher: String = "AES"): Array[Byte] = {
    val key = new SecretKeySpec(salt.getBytes, "AES")
    val cipherI = Cipher.getInstance(cipher, "SunJCE")
    cipherI.init(Cipher.DECRYPT_MODE, key)
    cipherI.doFinal(payload)
  }
}
