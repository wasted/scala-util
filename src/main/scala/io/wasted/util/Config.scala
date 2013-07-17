package io.wasted.util

import com.typesafe.config.ConfigFactory
import java.net.InetSocketAddress
import scala.collection.JavaConverters._

/**
 * Wrapper around Typesafe [[http://typesafehub.github.com/config/latest/api/com/typesafe/config/ConfigFactory.html ConfigFactory]].
 * Cache results in vals as i expect most of these operations to be expensive at some point or another.
 */
object Config {
  val conf = ConfigFactory.load()

  def getInt(name: String): Option[Int] = Tryo(conf.getInt(name))
  def getInt(name: String, fallback: Int): Int = getInt(name) getOrElse fallback

  def getBool(name: String): Option[Boolean] = Tryo(conf.getBoolean(name))
  def getBool(name: String, fallback: Boolean): Boolean = getBool(name) getOrElse fallback

  def getString(name: String): Option[String] = Tryo(conf.getString(name))
  def getString(name: String, fallback: String): String = getString(name) getOrElse fallback

  /**
   * Get a list of Strings out of your configuration.
   *
   * Example:
   * In your configuration you have a key
   * listen.on=["foo", "bar", "baz"]
   *
   * @param name Name of the configuration directive
   * @return Seq of Strings
   */
  def getStringList(name: String): Option[Seq[String]] = conf.getStringList(name).asScala.toList match {
    case l: List[String] @unchecked if l.length > 0 => Some(l)
    case _ => None
  }

  /**
   * Get a list of Strings out of your configuration.
   *
   * Example:
   * In your configuration you have a key
   * listen.on=["foo", "bar", "baz"]
   *
   * @param name Name of the configuration directive
   * @param fallback Fallback value if nothing is found
   * @return Seq of Strings
   */
  def getStringList(name: String, fallback: Seq[String]): Seq[String] = getStringList(name) getOrElse fallback

  /**
   * Get a list of InetSocketAddresses back where you configured multiple IPs in your configuration.
   *
   * Example:
   * In your configuration you have a key
   * listen.on=["127.0.0.1:8080" ,"[2002:123::123]:5", ..]
   *
   * @param name Name of the configuration directive
   * @return Seq of InetSocketAddresses to be used
   */
  def getInetAddrList(name: String): Option[Seq[InetSocketAddress]] = {
    val valid = conf.getStringList(name).asScala.flatMap(InetPrefix.stringToInetAddr)
    if (valid.length > 0) Some(valid) else None
  }

  /**
   * Get a list of InetSocketAddresses back where you configured multiple IPs in your configuration.
   *
   * Example:
   * In your configuration you have a key
   * listen.on=["127.0.0.1:8080" ,"[2002:123::123]:5", ..]
   *
   * @param name Name of the configuration directive
   * @param fallback Fallback value if nothing is found
   * @return Seq of InetSocketAddresses to be used
   */
  def getInetAddrList(name: String, fallback: Seq[String]): Seq[InetSocketAddress] =
    getInetAddrList(name) getOrElse fallback.flatMap(InetPrefix.stringToInetAddr)
}

