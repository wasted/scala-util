package io.wasted.util.ssl

import io.wasted.util._
import java.security.cert.X509Certificate
import java.security.KeyStore
import javax.net.ssl._
import java.io.InputStream
import collection.mutable.{ Map => MutableMap }

/*
 * Creates JavaSSL Engines on behalf of the Ssl singleton
 */
object JavaSSL extends Logger {
  private[this] val contextCache: MutableMap[String, SSLContext] = MutableMap.empty
  private[this] val algorithm = "sunx509"
  private[this] val protocol = "TLS"
  private[this] lazy val defaultSSLContext: SSLContext = {
    val ctx = SSLContext.getInstance(protocol)
    ctx.init(null, null, null)
    ctx
  }

  /**
   * Get an SSL server via JavaSSL
   *
   * @param certificate InputStream of the certificate
   * @param secret Secret to open the certificate
   * @param keyStoreType Type of the File
   * @param useCache Use a cache of SSL contexts, keyed on certificatePath
   * @return an SSLEngine
   */
  def server(certificate: InputStream, secret: String, keyStoreType: KeyStoreType.Value): Option[Engine] = Tryo {
    val secretArray = secret.toCharArray()

    val ks = KeyStore.getInstance(keyStoreType.toString)
    ks.load(certificate, secretArray)

    val kmf = KeyManagerFactory.getInstance(algorithm)
    kmf.init(ks, secretArray)
    info("JavaSSL context instantiated for Byte-Array certificate")

    val context = SSLContext.getInstance(protocol)
    context.init(kmf.getKeyManagers(), null, null)
    new Engine(context.createSSLEngine())
  }

  /**
   * Get an SSL server via JavaSSL
   *
   * @param certificatePath The path to the certificate file
   * @param secret Secret to open the certificate
   * @param keyStoreType Type of the File
   * @param useCache Use a cache of SSL contexts, keyed on certificatePath
   * @return an SSLEngine
   */
  def server(certificatePath: String, secret: String, keyStoreType: KeyStoreType.Value, useCache: Boolean = true): Option[Engine] = Tryo {
    def makeContext: SSLContext = {
      val context = SSLContext.getInstance(protocol)
      val kms = KeyManager(certificatePath, secret, keyStoreType)
      context.init(kms, null, null)
      info("JavaSSL context instantiated for certificate '%s'".format(certificatePath))
      context
    }

    val context = synchronized {
      if (useCache) contextCache.getOrElseUpdate(certificatePath, makeContext)
      else makeContext
    }

    new Engine(context.createSSLEngine())
  }

  /**
   * Get a client
   */
  def client(): Engine = new Engine(defaultSSLContext.createSSLEngine())

  /**
   * Get a client from the given Context
   */
  def client(ctx: SSLContext): Engine = {
    val sslEngine = ctx.createSSLEngine()
    sslEngine.setUseClientMode(true)
    new Engine(sslEngine)
  }

  /**
   * Get a SSLEngine for clients for given host and port.
   *
   * @param host Hostname
   * @param port Port
   */
  def client(host: String, port: Int): Engine = {
    val sslEngine = defaultSSLContext.createSSLEngine(host, port)
    sslEngine.setUseClientMode(true)
    new Engine(sslEngine)
  }

  /**
   * Get a client that skips verification of certificates.
   *
   * Security Warning: This defeats the purpose of SSL.
   */
  def clientWithoutCertificateValidation(): Engine =
    client(trustAllCertificates())

  private[this] def client(trustManagers: Array[TrustManager]): Engine = {
    val ctx = SSLContext.getInstance(protocol)
    ctx.init(null, trustManagers, null)
    new Engine(ctx.createSSLEngine())
  }

  /**
   * @return a trust manager chain that does not validate certificates
   */
  private[this] def trustAllCertificates(): Array[TrustManager] =
    Array(new IgnorantTrustManager)

  /**
   * A trust manager that does not validate anything
   */
  private[this] class IgnorantTrustManager extends X509TrustManager {
    def getAcceptedIssuers(): Array[X509Certificate] = new Array[X509Certificate](0)

    def checkClientTrusted(certs: Array[X509Certificate], authType: String) {
      // Do nothing.
    }

    def checkServerTrusted(certs: Array[X509Certificate], authType: String) {
      // Do nothing.
    }
  }
}

