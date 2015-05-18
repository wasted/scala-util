package io.wasted

import java.io.{ InputStream, PrintWriter, StringWriter }
import java.security.KeyStore
import javax.net.ssl.{ KeyManagerFactory, KeyManager }

import com.twitter.util.{ Duration => TD }
import io.netty.channel.{ ChannelFuture, ChannelFutureListener }
import io.netty.handler.ssl.SslContextBuilder

import scala.concurrent.duration.{ Duration => SD }

/**
 * Helpers
 */
package object util {
  implicit val hashingAlgo = HashingAlgo()
  implicit val hexingAlgo = HexingAlgo()
  implicit val cryptoCipher = CryptoCipher()

  /**
   * Transforms StackTraces into a String using StringWriter.
   */
  def stackTraceToString(throwable: Throwable) = {
    val w = new StringWriter
    throwable.printStackTrace(new PrintWriter(w))
    w.toString
  }

  implicit val implicitStackTraceToString = stackTraceToString _

  implicit val durationScala2Twitter: SD => TD = (sd) => TD(sd.length, sd.unit)
  implicit val durationTwitter2Scala: TD => SD = (td) => SD(td.inTimeUnit._1, td.inTimeUnit._2)

  implicit val channelFutureListener: (ChannelFuture => Any) => ChannelFutureListener = { pf =>
    new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = pf(f)
    }
  }

  object KeyStoreType extends Enumeration {
    val P12 = Value("PKCS12")
    val JKS = Value("JKS")
  }

  implicit class OurSslBuilder(val builder: SslContextBuilder) extends AnyVal {
    def keyManager(store: InputStream, secret: String, keyStoreType: KeyStoreType.Value): SslContextBuilder = {
      val secretArray = secret.toCharArray
      val ks = KeyStore.getInstance(keyStoreType.toString)
      ks.load(store, secretArray)

      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(ks, secretArray)

      builder.keyManager(kmf)
    }
  }
}

