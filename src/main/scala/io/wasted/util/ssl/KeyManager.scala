package io.wasted.util.ssl

import java.io._
import java.security.KeyStore
import java.util.Random
import javax.net.ssl._

/**
 * Available KeyStoreTypes
 */
object KeyStoreType extends Enumeration {
  val P12 = Value("PKCS12")
  val JKS = Value("JKS")
}

/**
 * Helps us creating KeyManagers from certificates
 */
object KeyManager {
  private[this] val algorithm = "sunx509"

  /**
   * Create a KeyManager from a given File
   *
   * @param certificatePath Path where to find the certificate
   * @param secret Secret to open the certificate
   * @param keyStoreType Type of the File
   */
  def apply(certificatePath: String, secret: String, keyStoreType: KeyStoreType.Value): Array[KeyManager] = {
    val file = new File(certificatePath)
    val fileIs = new FileInputStream(file)
    makeKeystore(fileIs, secret, keyStoreType)
  }

  private[this] def makeKeystore(store: InputStream, secret: String, keyStoreType: KeyStoreType.Value): Array[KeyManager] = {
    val secretArray = secret.toCharArray()
    val ks = KeyStore.getInstance(keyStoreType.toString)
    ks.load(store, secretArray)

    val kmf = KeyManagerFactory.getInstance(algorithm)
    kmf.init(ks, secretArray)

    kmf.getKeyManagers
  }
}

