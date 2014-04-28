package io.wasted.util

import com.typesafe.config.ConfigFactory
import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.collection.JavaConverters._
import java.util.concurrent.TimeUnit

/**
 * Wrapper around Typesafe [[http://typesafehub.github.com/config/latest/api/com/typesafe/config/ConfigFactory.html ConfigFactory]].
 * Cache results in vals as i expect most of these operations to be expensive at some point or another.
 */
object Config {
  val conf = ConfigFactory.load()

  /**
   * Gets the Bytes from Config
   * @param name Config directive
   * @return Option for a Long
   */
  def getBytes(name: String): Option[Long] = Tryo(conf.getBytes(name))

  /**
   * Gets the Bytes from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a Long
   */
  def getBytes(name: String, fallback: Long): Long = getBytes(name) getOrElse fallback

  /**
   * Gets the Duration from Config
   * @param name Config directive
   * @return Option for a Duration
   */
  def getDuration(name: String): Option[Duration] = Tryo(conf.getDuration(name, TimeUnit.MILLISECONDS).millis)

  /**
   * Gets the Duration from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a Duration
   */
  def getDuration(name: String, fallback: Duration): Duration = getDuration(name) getOrElse fallback

  /**
   * Gets the Int from Config
   * @param name Config directive
   * @return Option for an Integer
   */
  def getInt(name: String): Option[Int] = Tryo(conf.getInt(name))

  /**
   * Gets the Int from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a Integer
   */
  def getInt(name: String, fallback: Int): Int = getInt(name) getOrElse fallback

  /**
   * Gets the Double from Config
   * @param name Config directive
   * @return Option for an Double
   */
  def getDouble(name: String): Option[Double] = Tryo(conf.getDouble(name))

  /**
   * Gets the Double from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a Double
   */
  def getDouble(name: String, fallback: Double): Double = getDouble(name) getOrElse fallback

  /**
   * Gets the Boolean from Config
   * @param name Config directive
   * @return Option for a Boolean
   */
  def getBool(name: String): Option[Boolean] = Tryo(conf.getBoolean(name))

  /**
   * Gets the Bool from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a Bool
   */
  def getBool(name: String, fallback: Boolean): Boolean = getBool(name) getOrElse fallback

  /**
   * Gets the String from Config
   * @param name Config directive
   * @return Option for a String
   */
  def get(name: String): Option[String] = Tryo(conf.getString(name))

  /**
   * Gets the String from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a String
   */
  def get(name: String, fallback: String): String = getString(name) getOrElse fallback

  /**
   * Gets the String from Config
   * @param name Config directive
   * @return Option for aString
   */
  def getString(name: String): Option[String] = Tryo(conf.getString(name))

  /**
   * Gets the String from Config and uses a fallback
   * @param name Config directive
   * @param fallback Fallback value if nothing is found
   * @return Option for a String
   */
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
  def getStringList(name: String): Option[Seq[String]] = Tryo(conf.getStringList(name).asScala.toList) match {
    case Some(l: List[String] @unchecked) if l.length > 0 => Some(l)
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
    val valid = Tryo(conf.getStringList(name).asScala.toList) getOrElse List() flatMap InetPrefix.stringToInetAddr
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

