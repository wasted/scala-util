package io.wasted.util.ssl

import java.io.InputStream
import javax.net.ssl._

import io.wasted.util.Logger

case class Engine(self: SSLEngine, handlesRenegotiation: Boolean = false, certId: String = "<unknown>")

/*
 * SSL helper object, capable of creating cached SSLEngine instances
 * backed by both the native APR/OpenSSL bindings, or pure Java JavaSSL.
 */
object Ssl extends Logger {
  /**
   * Get a server engine using native JavaSSL
   *
   * @param certificate InputStream of the certificate
   * @param secret Secret key to open the file
   * @param keyStoreType Type of the File
   * @return a SSLEngine
   */
  def server(certificate: InputStream, secret: String, keyStoreType: KeyStoreType.Value): Engine = {
    val jsseInstance = JavaSSL.server(certificate, secret, keyStoreType)
    require(jsseInstance.isDefined, "Could not create an SSLEngine")
    jsseInstance.get
  }

  /**
   * Get an SSL context via JavaSSL
   *
   * @param certificate InputStream of the certificate
   * @param secret Secret to open the certificate
   * @param keyStoreType Type of the File
   * @return a SSLContext
   */
  def context(certificate: InputStream, secret: String, keyStoreType: KeyStoreType.Value): SSLContext = {
    val jsseInstance = JavaSSL.context(certificate, secret, keyStoreType)
    require(jsseInstance.isDefined, "Could not create an SSLContext")
    jsseInstance.get
  }

  /**
   * Get a server engine, using the native OpenSSL provider if available
   *
   * @param certificatePath The path to the certificate file
   * @param secret Secret key to open the file
   * @param keyStoreType Type of the File
   * @param ciphers [OpenSSL] The ciphers
   * @param nextProtos [OpenSSL] The nextProtos available
   * @throws RuntimeException if no provider could be initialized
   * @return a SSLEngine
   */
  def server(
    certificatePath: String,
    secret: String,
    keyStoreType: KeyStoreType.Value,
    ciphers: Option[String] = None,
    nextProtos: Option[String] = None,
    cacheContexts: Boolean = true): Engine = {
    def jks() = {
      val jsseInstance = JavaSSL.server(certificatePath, secret, keyStoreType, cacheContexts)
      require(jsseInstance.isDefined, "Could not create an SSLEngine")
      jsseInstance.get
    }

    keyStoreType match {
      case KeyStoreType.JKS => jks
      case KeyStoreType.P12 =>
        val nativeInstance = OpenSSL.server(certificatePath, secret, ciphers, nextProtos, cacheContexts)
        nativeInstance.getOrElse {
          require(ciphers.isEmpty, "'Ciphers' parameter unsupported with Java's native SSL provider")
          require(nextProtos.isEmpty, "'Next Protocols' parameter unsupported with Java's native SSL provider")
          jks()
        }
    }
  }

  /* Default Client SSLContext. */
  val defaultClientSSLContext: SSLContext = {
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, null, null)
    ctx
  }

  /**
   * Get a client engine
   */
  def client(): Engine = JavaSSL.client()

  /**
   * Get a client engine, from the given context
   */
  def client(sslContext: SSLContext): Engine = JavaSSL.client(sslContext)

  /**
   * Get a SSLEngine for clients for given host and port.
   *
   * @param host Hostname
   * @param port Port
   */
  def client(host: String, port: Int): Engine = JavaSSL.client(host, port)

  /**
   * Get a client engine that doesn't check the validity of certificates
   */
  def clientWithoutCertificateValidation(): Engine = JavaSSL.clientWithoutCertificateValidation()
}

