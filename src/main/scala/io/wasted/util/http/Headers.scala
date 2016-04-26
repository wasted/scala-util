package io.wasted.util.http

import io.netty.handler.codec.http.HttpHeaderNames._
import io.netty.handler.codec.http.HttpRequest

import scala.collection.JavaConversions._

trait WastedHttpHeaders {
  def get(key: io.netty.util.AsciiString): Option[String] = getAll(key.toString).headOption
  def get(key: String): Option[String] = getAll(key).headOption
  def apply(key: io.netty.util.AsciiString): String = get(key).getOrElse(scala.sys.error("Header doesn't exist"))
  def apply(key: String): String = get(key).getOrElse(scala.sys.error("Header doesn't exist"))
  def getAll(key: String): Iterable[String]
  val length: Int
  lazy val cors: Map[String, String] = {
    for {
      corsMethods <- this.get(ACCESS_CONTROL_REQUEST_METHOD)
      corsHeaders <- this.get(ACCESS_CONTROL_REQUEST_HEADERS)
      corsOrigin <- this.get(ORIGIN.toString)
    } yield Map(
      ACCESS_CONTROL_ALLOW_METHODS.toString -> corsMethods,
      ACCESS_CONTROL_ALLOW_HEADERS.toString -> corsHeaders,
      ACCESS_CONTROL_ALLOW_ORIGIN.toString -> corsOrigin)
  } getOrElse Map()
}

/**
 * Parser HTTP Request headers and give back a nice map
 * @param corsOrigin Origin for CORS Request if we want to add them onto a HTTP Request
 */
class Headers(corsOrigin: String = "*") {
  def get(request: HttpRequest): WastedHttpHeaders = {
    val headers: Map[String, Seq[String]] = request.headers.names.map(key =>
      key.toLowerCase -> Seq(request.headers.get(key))).toMap

    new WastedHttpHeaders {
      def getAll(key: String): Iterable[String] = headers.getOrElse(key.toLowerCase, Seq())
      override def toString = headers.toString()
      override lazy val length = headers.size
    }
  }
}
