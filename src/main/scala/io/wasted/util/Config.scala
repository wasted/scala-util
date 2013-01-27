package io.wasted.util

import com.typesafe.config.ConfigFactory

/**
 * Wrapper around Typesafe [[http://typesafehub.github.com/config/latest/api/com/typesafe/config/ConfigFactory.html ConfigFactory]].
 */
object Config {
  private val conf = ConfigFactory.load()

  def getOptionString(name: String) = Tryo(conf.getString(name))
  def getString(name: String, fallback: String) = getOptionString(name) getOrElse fallback

  def getOptionStringList(name: String, sep: String = ","): Option[List[String]] = getOptionString(name) match {
    case Some(o) => Some(o.split(sep).map(_.trim).toList)
    case _ => None
  }
  def getStringList(name: String, fallback: List[String]): List[String] = getOptionStringList(name) getOrElse fallback

  def getOptionInt(name: String) = Tryo(conf.getInt(name))
  def getInt(name: String, fallback: Int) = getOptionInt(name) getOrElse fallback

  def getOptionBool(name: String) = Tryo(conf.getBoolean(name))
  def getBool(name: String, fallback: Boolean) = getOptionBool(name) getOrElse fallback
}

