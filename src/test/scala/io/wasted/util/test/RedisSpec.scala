package io.wasted.util.test

import com.twitter.util.Await
import io.netty.util.CharsetUtil
import io.wasted.util.Logger
import io.wasted.util.redis._
import org.scalatest._

class RedisSpec extends WordSpec with Logger {

  val clientF = RedisClient().connectTo("localhost", 6379).open()

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
      assert(Await.result(client.hMGet("hashmap", "key2" :: "key1" :: Nil)) == Map("key2" -> "value2", "key1" -> "value1"))
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

    /*"hStrLen" in {
      assert(Await.result(client.hStrLen("hash", "key1")) == 6L, "hash string length is wrong")
    }*/

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

    "mSetNx" in {
      assert(Await.result(client.mSetNx(Map("mset1" -> "shit", "int" -> "5"))) == false, "Should not have set because of int!")
    }

    "mSet" in {
      Await.result(client.mSet(Map("mset1" -> "shit", "mset2" -> "shit")))
    }

    "mGet" in {
      assert(Await.result(client.mGet("mset1" :: "mset2" :: Nil)) == Map("mset1" -> "shit", "mset2" -> "shit"), "Should have returned our Map")
    }

    "sAdd" in {
      assert(Await.result(client.sAdd("set1", "member1" :: "member2" :: "member3" :: Nil)) == 3, "wrong number of members added")
      assert(Await.result(client.sAdd("set2", "member1" :: "member2" :: Nil)) == 2, "wrong number of members added")

    }

    "sCard" in {
      assert(Await.result(client.sCard("set1")) == 3, "wrong number of members counted")
      assert(Await.result(client.sCard("set2")) == 2, "wrong number of members counted")
    }

    "sDiff" in {
      assert(Await.result(client.sDiff("set1", "set2")) == List("member3"), "wrong number of members diff'd")
    }

    "sDiffStore" in {
      assert(Await.result(client.sDiffStore("setDiff", "set1", "set2" :: Nil)) == 1, "wrong number of members diff'd")
    }

    "sInter" in {
      assert(Await.result(client.sInter("set1", "set2")) == List("member1", "member2"), "wrong number of members inter'd")
    }

    "sInterStore" in {
      assert(Await.result(client.sInterStore("setInter", "set1", "set2" :: Nil)) == 2, "wrong number of members inter'd")
    }

    "sIsMember" in {
      assert(Await.result(client.sIsMember("set1", "member3")), "member3 not member of set1")
      assert(Await.result(client.sIsMember("set2", "member3")) == false, "member3 is member of set2")
    }

    "sMembers" in {
      assert(Await.result(client.sMembers("setInter")) == List("member1", "member2"), "wrong members inter'd")
    }

    "sMove" in {
      assert(Await.result(client.sMove("set1", "set2", "member3")), "did not move member3")
    }

    "sPop" in {
      assert(Await.result(client.sPop("set1")).startsWith("member"), "wrong member pop'd")
    }

    "sRandMember" in {
      assert(Await.result(client.sRandMember("setInter")).startsWith("member"), "wrong members rand'd")
    }

    "sRem" in {
      assert(Await.result(client.sRem("set2", "member3" :: Nil)) == 1, "other than 1 member rm'd")
    }

    "sUnion" in {
      assert(Await.result(client.sUnion("set1", "set2")) == List("member1", "member2"), "wrong number of members union'd")
    }

    "sUnionInter" in {
      assert(Await.result(client.sUnionStore("setInter", "set1" :: "set2" :: Nil)) == 2, "wrong number of members union'd")
    }

    "setEx" in {
      Await.result(client.setEx("setInter", "new", 5))
    }

    "setNx" in {
      assert(Await.result(client.setNx("setInter", "evennever")) == false, "was able to update setInter")
    }

    "getSet" in {
      assert(Await.result(client.getSet("setInter", "evennewer")) == "new", "getSet is weir" +
        "" +
        "")
    }

    "flushDB" in {
      Await.result(client.flushDB())
    }

    "disconnect" in {
      Await.result(client.close())
    }

  }

}

