package io.wasted.util
package redis

import com.twitter.util.{ Duration, Future }

class OverRateLimitException(val name: String, val window: Duration, val limit: Long, val prefix: Option[String])
  extends Exception("Rate-Limit " + prefix.map("at " + _ + " ").getOrElse("") + "for " + name + " with Limit of " + limit + " in " + window.inSeconds + " seconds")
  with scala.util.control.NoStackTrace

final case class CounterBasedRateLimiter(client: NettyRedisChannel, window: Duration, limit: Long, prefix: Option[String] = None) {
  def apply(name: String): Future[Unit] = {
    val key = "ratelimit:" + prefix.map(_ + ":").getOrElse("") + name
    client.incr(key).flatMap { count =>
      if (count > limit) Future.exception(new OverRateLimitException(name, window, limit, prefix))
      else {
        if (count == 1) client.expire(key, window)
        Future.Done
      }
    }
  }
}