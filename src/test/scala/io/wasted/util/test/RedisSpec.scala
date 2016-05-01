package io.wasted.util.test

import java.net.URI

import com.twitter.util.Await
import io.netty.util.{ CharsetUtil, ReferenceCountUtil }
import io.wasted.util.Logger
import io.wasted.util.redis._
import org.scalatest._

class RedisSpec extends WordSpec with Logger {

  val uri = new URI("redis://localhost:6379")
  val clientF = RedisClient().connectTo("localhost", 6379).open(uri, uri)

  val baseKey = "mytest"
  val baseStr = "myval"

  var client: NettyRedisChannel = _

  "Testing redis client functionality" should {
    "connect" in {
      client = Await.result(clientF)
    }
    "simple set key/value" in {
      Await.result(client.set(baseKey, baseStr))
    }
    "simple get key/value" in {
      assert(Await.result(client.get(baseKey)) == baseStr, "did not match our value")
    }

    "simple append key/value" in {
      assert(Await.result(client.append(baseKey, baseStr)) == (baseStr + baseStr).length, "value too long")
    }

    "client list" in {
      Await.result(client.clientList().map(x => warn(x.content().toString(CharsetUtil.UTF_8))))
    }

    "dbSize" in {
      assert(Await.result(client.dbSize()) == 1L, "not a fresh database?")
    }

    "incr" in {
      assert(Await.result(client.incr("int")) == 1L, "not a fresh database?")
    }

    "incrBy" in {
      assert(Await.result(client.incrBy("int", 2)) == 3L, "not a fresh database?")
    }

    "decr" in {
      assert(Await.result(client.decr("int")) == 2L, "not a fresh database?")
    }

    "decrBy" in {
      assert(Await.result(client.decrBy("int", 2)) == 0L, "not a fresh database?")
    }

    "incrByFloat" in {
      assert(Await.result(client.incrByFloat("int", 2.5f)) == 2.5f, "not a fresh database?")
    }

    "echo" in {
      assert(Await.result(client.echo("oink")) == "oink", "did not echo oink")
    }

    "exists" in {
      assert(Await.result(client.exists("int")), "field 'int' did not exist")
    }

    "expire" in {
      assert(Await.result(client.expire("int", 5)), "expire did not set correctly")
    }

    "expireAt" in {
      assert(Await.result(client.expireAt(baseKey, new java.util.Date().getTime / 1000 + 10)), "expire did not set correctly")
    }

    "ttl" in {
      assert(Await.result(client.ttl("int")) == 5L, "expire did not set correctly to 5")
      assert(Await.result(client.ttl(baseKey)) >= 9, "expireAt did not set correctly to 10")
    }

    "hMSet" in {
      Await.result(client.hMSet("hashmap", Map("key1" -> "value1", "key2" -> "value2")))
    }

    "hMGet" in {
      assert(Await.result(client.hMGet("hashmap", "key2", "key1")) == Map("key2" -> "value2", "key1" -> "value1"))
    }

    "hSet" in {
      assert(Await.result(client.hSet("hash", "key1", "value1")), "Hash did not set properly")
    }

    "hGet" in {
      assert(Await.result(client.hGet("hash", "key1")) == "value1", "Hash did not set correctly")
    }

    "hGetAll" in {
      assert(Await.result(client.hGetAll("hash")) == Map("key1" -> "value1"), "Not all keys in hash")
    }

    "hKeys" in {
      assert(Await.result(client.hKeys("hash")) == List("key1"), "Weird keys in hash")
    }

    "hVals" in {
      assert(Await.result(client.hVals("hash")) == List("value1"), "Weird values in hash")
    }

    "hIncrBy" in {
      assert(Await.result(client.hIncrBy("hash", "incr", 2)) == 2L, "hIncrBy is wrong")
    }

    "hIncrByFloat" in {
      assert(Await.result(client.hIncrByFloat("hash", "incrF", 2.5f)) == 2.5f, "hIncrByFloat is wrong")
    }

    "hLen" in {
      assert(Await.result(client.hLen("hash")) == 3L, "hash count is wrong")
    }

    "hStrLen" in {
      assert(Await.result(client.hStrLen("hash", "key1")) == 6L, "hash string length is wrong")
    }

    "hSetNx" in {
      assert(Await.result(client.hSetNx("hash", "key1", "value2")) == false, "hash set nx should not have set")
      assert(Await.result(client.hSetNx("hash", "key2", "value2")), "hash set nx should have set")
    }

    "hExists" in {
      assert(Await.result(client.hExists("hash", "nx")) == false, "Should not have key2")
      assert(Await.result(client.hExists("hash", "key1")), "Should have key1")
    }

    "hDel" in {
      assert(Await.result(client.hDel("hash", "nx")) == false, "Should not be able to delete key2")
      assert(Await.result(client.hDel("hash", "key1")), "Should be able to delete key1")
    }

    "flushDB" in {
      Await.result(client.flushDB())
    }

    "disconnect" in {
      Await.result(client.close())
    }

  }

}

