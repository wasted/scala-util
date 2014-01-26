package io.wasted.util.http

import io.netty.handler.codec.http.HttpHeaders.Names._
import io.netty.handler.codec.http.HttpRequest
import scala.collection.JavaConversions._

trait HttpHeaders {
  def get(key: String): Option[String] = getAll(key).headOption
  def apply(key: String): String = get(key).getOrElse(scala.sys.error("Header doesn't exist"))
  def getAll(key: String): Iterable[String]
  val length: Int
  lazy val cors: Map[String, String] = {
    for {
      corsMethods <- this.get(ACCESS_CONTROL_REQUEST_METHOD)
      corsHeaders <- this.get(ACCESS_CONTROL_REQUEST_HEADERS)
      corsOrigin <- this.get(ORIGIN)
    } yield Map(
      ACCESS_CONTROL_ALLOW_METHODS -> corsMethods,
      ACCESS_CONTROL_ALLOW_HEADERS -> corsHeaders,
      ACCESS_CONTROL_ALLOW_ORIGIN -> corsOrigin)
  } getOrElse Map()
}

/**
 * Parser HTTP Request headers and give back a nice map
 * @param corsOrigin Origin for CORS Request if we want to add them onto a HTTP Request
 */
class Headers(corsOrigin: String = "*") {
  def get(request: HttpRequest): HttpHeaders = {
    val headers: Map[String, Seq[String]] = request.headers.names.map(key =>
      key.toLowerCase -> Seq(request.headers.get(key))).toMap

    new HttpHeaders {
      def getAll(key: String): Iterable[String] = headers.get(key.toLowerCase) getOrElse Seq()
      override def toString = headers.toString()
      override lazy val length = headers.size
    }
  }
}
