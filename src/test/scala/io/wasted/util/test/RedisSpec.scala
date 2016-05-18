package io.wasted.util.test

import com.twitter.conversions.time._
import com.twitter.util.Await
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
      client = Await.result(clientF, 1.second)
    }
    "simple set key/value" in {
      Await.result(client.set(baseKey, baseStr), 1.second)
    }
    "simple get key/value" in {
      assert(Await.result(client.get(baseKey), 1.second).contains(baseStr), "did not match our value")
      assert(Await.result(client.get("non-existing"), 1.second).isEmpty, "did not match our value")
    }

    "strlen" in {
      assert(Await.result(client.strLen(baseKey), 1.second) == baseStr.length, "did not count right?")
    }

    "type" in {
      assert(Await.result(client.`type`(baseKey), 1.second) == "string")
    }

    "time" in {
      assert(Await.result(client.time(), 1.second)._1 <= new java.util.Date().getTime / 1000, "Time on redis is further along?")
    }

    "simple append key/value" in {
      assert(Await.result(client.append(baseKey, baseStr), 1.second) == (baseStr + baseStr).length, "value too long")
    }

    "dbSize" in {
      assert(Await.result(client.dbSize(), 1.second) == 1L, "not a fresh database?")
    }

    "incr" in {
      assert(Await.result(client.incr("int"), 1.second) == 1L, "not a fresh database?")
    }

    "incrBy" in {
      assert(Await.result(client.incrBy("int", 2), 1.second) == 3L, "not a fresh database?")
    }

    "decr" in {
      assert(Await.result(client.decr("int"), 1.second) == 2L, "not a fresh database?")
    }

    "decrBy" in {
      assert(Await.result(client.decrBy("int", 2), 1.second) == 0L, "not a fresh database?")
    }

    "incrByFloat" in {
      assert(Await.result(client.incrByFloat("int", 2.5f), 1.second) == 2.5f, "not a fresh database?")
    }

    "echo" in {
      assert(Await.result(client.echo("oink"), 1.second) == "oink", "did not echo oink")
    }

    "exists" in {
      assert(Await.result(client.exists("int"), 1.second), "field 'int' did not exist")
    }

    "expire" in {
      assert(Await.result(client.expire("int", 5), 1.second), "expire did not set correctly")
    }

    "expireAt" in {
      assert(Await.result(client.expireAt(baseKey, new java.util.Date().getTime / 1000 + 10), 1.second), "expire did not set correctly")
    }

    "ttl" in {
      assert(Await.result(client.ttl("int"), 1.second) == 5L, "expire did not set correctly to 5")
    }

    "hMSet" in {
      Await.result(client.hMSet("hashmap", Map("key1" -> "value1", "key2" -> "value2")), 1.second)
    }

    "hMGet" in {
      assert(Await.result(client.hMGet("hashmap", "key2" :: "key1" :: Nil)) == Map("key2" -> "value2", "key1" -> "value1"), 1.second)
    }

    "hSet" in {
      assert(Await.result(client.hSet("hash", "key1", "value1"), 1.second), "Hash did not set properly")
    }

    "hGet" in {
      assert(Await.result(client.hGet("hash", "key1"), 1.second).contains("value1"), "Hash did not set correctly")
      assert(Await.result(client.hGet("hash", "key2"), 1.second).isEmpty, "Hash did not get correctly")
    }

    "hGetAll" in {
      assert(Await.result(client.hGetAll("hash"), 1.second) == Map("key1" -> "value1"), "Not all keys in hash")
    }

    "hKeys" in {
      assert(Await.result(client.hKeys("hash"), 1.second) == List("key1"), "Weird keys in hash")
    }

    "hVals" in {
      assert(Await.result(client.hVals("hash"), 1.second) == List("value1"), "Weird values in hash")
    }

    "hIncrBy" in {
      assert(Await.result(client.hIncrBy("hash", "incr", 2), 1.second) == 2L, "hIncrBy is wrong")
    }

    "hIncrByFloat" in {
      assert(Await.result(client.hIncrByFloat("hash", "incrF", 2.5f), 1.second) == 2.5f, "hIncrByFloat is wrong")
    }

    "hLen" in {
      assert(Await.result(client.hLen("hash"), 1.second) == 3L, "hash count is wrong")
    }

    /*"hStrLen" in {
      assert(Await.result(client.hStrLen("hash", "key1"), 1.second) == 6L, "hash string length is wrong")
    }*/

    "hSetNx" in {
      assert(Await.result(client.hSetNx("hash", "key1", "value2"), 1.second) == false, "hash set nx should not have set")
      assert(Await.result(client.hSetNx("hash", "key2", "value2"), 1.second), "hash set nx should have set")
    }

    "hExists" in {
      assert(Await.result(client.hExists("hash", "nx"), 1.second) == false, "Should not have key2")
      assert(Await.result(client.hExists("hash", "key1"), 1.second), "Should have key1")
    }

    "hDel" in {
      assert(Await.result(client.hDel("hash", "nx"), 1.second) == false, "Should not be able to delete key2")
      assert(Await.result(client.hDel("hash", "key1"), 1.second), "Should be able to delete key1")
    }

    "mSetNx" in {
      assert(Await.result(client.mSetNx(Map("mset1" -> "shit", "int" -> "5")), 1.second) == false, "Should not have set because of int!")
    }

    "mSet" in {
      Await.result(client.mSet(Map("mset1" -> "shit", "mset2" -> "shit")), 1.second)
    }

    "mGet" in {
      assert(Await.result(client.mGet("mset1" :: "mset2" :: Nil), 1.second) == Map("mset1" -> "shit", "mset2" -> "shit"), "Should have returned our Map")
    }

    "sAdd" in {
      assert(Await.result(client.sAdd("set1", "member1" :: "member2" :: "member3" :: Nil), 1.second) == 3, "wrong number of members added")
      assert(Await.result(client.sAdd("set2", "member1" :: "member2" :: Nil), 1.second) == 2, "wrong number of members added")

    }

    "sCard" in {
      assert(Await.result(client.sCard("set1"), 1.second) == 3, "wrong number of members counted")
      assert(Await.result(client.sCard("set2"), 1.second) == 2, "wrong number of members counted")
    }

    "sDiff" in {
      assert(Await.result(client.sDiff("set1", "set2"), 1.second) == List("member3"), "wrong number of members diff'd")
    }

    "sDiffStore" in {
      assert(Await.result(client.sDiffStore("setDiff", "set1", "set2" :: Nil), 1.second) == 1, "wrong number of members diff'd")
    }

    "sInter" in {
      assert(Await.result(client.sInter("set1", "set2"), 1.second) == List("member2", "member1"), "wrong number of members inter'd")
    }

    "sInterStore" in {
      assert(Await.result(client.sInterStore("setInter", "set1", "set2" :: Nil), 1.second) == 2, "wrong number of members inter'd")
    }

    "sIsMember" in {
      assert(Await.result(client.sIsMember("set1", "member3"), 1.second), "member3 not member of set1")
      assert(Await.result(client.sIsMember("set2", "member3"), 1.second) == false, "member3 is member of set2")
    }

    "sMembers" in {
      assert(Await.result(client.sMembers("setInter"), 1.second) == List("member1", "member2"), "wrong members inter'd")
    }

    "sMove" in {
      assert(Await.result(client.sMove("set1", "set2", "member3"), 1.second), "did not move member3")
    }

    "sPop" in {
      assert(Await.result(client.sPop("set1"), 1.second).startsWith("member"), "wrong member pop'd")
    }

    "sRandMember" in {
      assert(Await.result(client.sRandMember("setInter"), 1.second).startsWith("member"), "wrong members rand'd")
    }

    "sRem" in {
      assert(Await.result(client.sRem("set2", "member3" :: Nil), 1.second) == 1, "other than 1 member rm'd")
    }

    "sUnion" in {
      assert(Await.result(client.sUnion("set1", "set2"), 1.second).diff(List("member1", "member2")).isEmpty, "wrong number of members union'd")
    }

    "sUnionInter" in {
      assert(Await.result(client.sUnionStore("setInter", "set1" :: "set2" :: Nil), 1.second) == 2, "wrong number of members union'd")
    }

    "setEx" in {
      Await.result(client.setEx("setInter", "new", 5), 1.second)
    }

    "persist" in {
      assert(Await.result(client.persist("setInter"), 1.second))
    }

    "setNx" in {
      assert(Await.result(client.setNx("setInter", "evennever"), 1.second) == false, "was able to update setInter")
    }

    "getSet" in {
      assert(Await.result(client.getSet("setInter", "evennewer"), 1.second) == "new", "getSet is weird")
    }

    "lPush" in {
      Await.result(client.lPush("mylist", "bar" :: "baz" :: Nil), 1.second)
    }

    "lRange" in {
      assert(Await.result(client.lRange("mylist", 1, 1), 1.second) == List("bar"), "lRange is weird")
    }

    "lTrim" in {
      Await.result(client.lTrim("mylist", 1, 1), 1.second)
    }

    "lIndex" in {
      assert(Await.result(client.lIndex("mylist", 0), 1.second).contains("bar"), "lIndex returned wrong value")
      assert(Await.result(client.lIndex("newlist", 5), 1.second).isEmpty, "lIndex returned wrong value")
    }

    "lInsertBefore" in {
      assert(Await.result(client.lInsertBefore("mylist", "bar", "shit"), 1.second) == 2L, "lInsert Before inserted wrong")
    }

    "lInsertAfter" in {
      assert(Await.result(client.lInsertAfter("mylist", "shit", "doodle"), 1.second) == 3L, "lInsert After inserted wrong")
    }

    "lLen" in {
      assert(Await.result(client.lLen("mylist"), 1.second) == 3L, "lLen returned wrong list length")
    }

    "lPop" in {
      assert(Await.result(client.lPop("mylist"), 1.second).contains("shit"), "lPop pop'd wrong element")
      assert(Await.result(client.lPop("newlist"), 1.second).isEmpty, "lPop pop'd wrong element")
    }

    "lPushX" in {
      assert(Await.result(client.lPushX("mylist", "bar"), 1.second) == 3L, "lPushX returned weird value")
    }

    "lRem" in {
      assert(Await.result(client.lRem("mylist", -2, "bar"), 1.second) == 2L, "wrong number of items lRem'd")
    }

    "lSet" in {
      Await.result(client.lSet("mylist", 0, "newitem"), 1.second)
    }

    "rPop" in {
      assert(Await.result(client.rPop("mylist"), 1.second).contains("newitem"), "rPop pop'd the wrong item")
      assert(Await.result(client.rPop("newlist"), 1.second).isEmpty, "rPop pop'd the wrong item")
    }

    "rPoplPush" in {
      Await.result(client.lPush("newlist", "fizzle" :: Nil), 1.second)
      assert(Await.result(client.rPoplPush("newlist", "mylist"), 1.second) == "fizzle", "rPoplPush'd wrong item!")
    }

    "rPush" in {
      assert(Await.result(client.rPush("newlist", "brittle" :: Nil), 1.second) == 1L, "rPush push'd at wrong index!")
    }

    "rPushX" in {
      assert(Await.result(client.rPushX("mylist", "newitem"), 1.second) == 2L, "rPushX returned weird value")
    }

    "pExpire" in {
      assert(Await.result(client.pExpire("newlist", 50000), 1.second), "pExpire did not work")
    }

    "pExpireAt" in {
      assert(Await.result(client.pExpireAt("newlist", new java.util.Date().getTime + 40000), 1.second), "pExpireAt did not work")
    }

    "pSetEx" in {
      Await.result(client.pSetEx("psetex", 10000, "crap"), 1.second)
    }

    "pTtl" in {
      assert(Await.result(client.pTtl("psetex"), 1.second) > 0, "pTtl returned wrong")
    }

    "pfAdd" in {
      assert(Await.result(client.pfAdd("hill1", "foo" :: "bar" :: "zap" :: "a" :: Nil), 1.second), "HyperLogLog internal register was not altered")
      assert(Await.result(client.pfAdd("hill2", "a" :: "b" :: "c" :: "foo" :: Nil), 1.second), "HyperLogLog internal register was not altered")
    }

    "pfMerge" in {
      Await.result(client.pfMerge("hill3", "hill1" :: "hill2" :: Nil), 1.second)
    }

    "pfCount" in {
      assert(Await.result(client.pfCount("hill3"), 1.second) == 6L, "HyperLogLog internal register was not merged")
    }

    "setBit" in {
      assert(Await.result(client.setBit("mykey", 7, true), 1.second) == 0L, "bit not set correctly")
      assert(Await.result(client.setBit("mykey", 7, false), 1.second) == 1L, "bit not set correctly")
    }

    "setRange" in {
      Await.result(client.set("key1", "Hello World"), 1.second)
      assert(Await.result(client.setRange("key1", 6, "redis"), 1.second) == 11, "setRange weirded out")
      assert(Await.result(client.get("key1"), 1.second).contains("Hello redis"), "setRange did not work")
    }

    "getBit" in {
      assert(Await.result(client.getBit("mykey", 7), 1.second) == false, "getBit returned a true?")
    }

    "getRange" in {
      assert(Await.result(client.getRange("key1", 0, 4), 1.second) == "Hello", "getRange did not return hello")
    }

    "bitCount" in {
      Await.result(client.set("key1", "foobar"), 1.second)
      assert(Await.result(client.bitCount("key1"), 1.second) == 26)
      assert(Await.result(client.bitCount("key1", 0, 0), 1.second) == 4)
      assert(Await.result(client.bitCount("key1", 1, 1), 1.second) == 6)
    }

    "randomKey" in {
      assert(Await.result(client.randomKey(), 1.second).isDefined)
    }

    "bitPos" in {
      Await.result(client.set("key1", new String(Array(0xff, 0xf0, 0x00).map(_.toChar))), 1.second)
      assert(Await.result(client.bitPos("key1", false), 1.second) == 2)
    }

    "bitOp" in {
      Await.result(client.set("key1", "foobar"), 1.second)
      Await.result(client.set("key2", "abcdef"), 1.second)
      assert(Await.result(client.bitOp("dest", RedisBitOperation.AND, Seq("key1", "key2")), 1.second) == 6L)
      assert(Await.result(client.bitOp("dest", RedisBitOperation.OR, Seq("key1", "key2")), 1.second) == 6L)
      assert(Await.result(client.bitOp("dest", RedisBitOperation.XOR, Seq("key1", "key2")), 1.second) == 6L)
      assert(Await.result(client.bitOp("dest", RedisBitOperation.NOT, "key1"), 1.second) == 6L)
    }

    "rename" in {
      Await.result(client.rename("key1", "key3"), 1.second)
    }

    "renameNx" in {
      assert(Await.result(client.renameNx("key3", "key1"), 1.second), "did overwrite Siciliy?")
      assert(Await.result(client.renameNx("key1", "key2"), 1.second) == false, "did overwrite key1")
    }

    "zAdd" in {
      assert(Await.result(client.zAdd("myzset", 1, "one", false), 1.second) == 1L)
      assert(Await.result(client.zAdd("myzset", 2, "two", true), 1.second) == 2L)
    }

    "zCard" in {
      assert(Await.result(client.zCard("myzset"), 1.second) == 2L)
    }

    "zCount" in {
      assert(Await.result(client.zAdd("myzset", 3, "three", false), 1.second) == 1.0)
      assert(Await.result(client.zCount("myzset"), 1.second) == 3L)
      assert(Await.result(client.zCount("myzset", "(1", "3"), 1.second) == 2L)
    }

    "zIncrBy" in {
      assert(Await.result(client.zIncrBy("myzset", 2, "one"), 1.second) == 3L)
    }

    /*"zInterStore" in {
      assert(Await.result(client.zInterStore(), 1.second).isDefined)
    }*/

    "zLexCount" in {
      assert(Await.result(client.zAdd("lexzset", List(0d -> "a", 0d -> "b", 0d -> "c", 0d -> "d", 0d -> "e"))) == 5d)
      assert(Await.result(client.zLexCount("lexzset"), 1.second) == 5L)
    }

    "zRange" in {
      assert(Await.result(client.zRange("lexzset", 0, -1), 1.second).length == 5)
    }

    "zRangeByLex" in {
      assert(Await.result(client.zRangeByLex("lexzset", "-", "[c"), 1.second).length == 3)
    }

    "zRevRangeByLex" in {
      assert(Await.result(client.zRevRangeByLex("lexzset", "[c", "-"), 1.second).length == 3)
    }

    "zRangeByScore" in {
      assert(Await.result(client.zRangeByScore("lexzset"), 1.second) == List("a", "b", "c", "d", "e"))
    }

    "zRank" in {
      assert(Await.result(client.zRank("myzset", "three"), 1.second) == 2L)
    }

    "zRem" in {
      assert(Await.result(client.zRem("myzset", "three"), 1.second) > 0)
    }

    "zRemRangeByLex" in {
      assert(Await.result(client.zRemRangeByLex("lexzset", "[alpha", "[omega"), 1.second) == 4)
    }

    "zRemRangeByRank" in {
      assert(Await.result(client.zRemRangeByRank("lexzset", 0, 1), 1.second) == 1)
    }

    "zRemRangeByScore" in {
      assert(Await.result(client.zRemRangeByScore("lexzset", "-inf", "(2"), 1.second) == 0)
    }

    "zRevRange" in {
      assert(Await.result(client.zRevRange("myzset", 0, -1), 1.second) == List("one", "two"))
    }

    "zRevRangeByScore" in {
      assert(Await.result(client.zRevRangeByScore("myzset"), 1.second) == List("one", "two"))
    }

    "zRevRank" in {
      assert(Await.result(client.zRevRank("myzset", "two"), 1.second) == 1L)
    }

    "zScore" in {
      assert(Await.result(client.zScore("myzset", "two"), 1.second) == 2d)
    }

    /*"zUnionStore" in {
      assert(Await.result(client.zUnionStore(), 1.second).isDefined)
    }

    "zScan" in {
      assert(Await.result(client.zScan(), 1.second).isDefined)
    }*/

    /*
    "geoAdd" in {
      val palermo = RedisGeoObject(13.361389, 38.115556, "Palermo")
      val catania = RedisGeoObject(15.087269, 37.502669, "Catania")
      assert(Await.result(client.geoAdd("Sicily", palermo :: catania :: Nil), 1.second) == 2L)
    }

    "geoDist" in {
      assert(Await.result(client.geoDist("Sicily", "Palermo", "Catania", RedisGeoUnit.Meters), 1.second) == 166274.15156960039)
    }

    "geoHash" in {
      assert(Await.result(client.geoHash("Sicily", "Palermo" :: "Catania" :: Nil), 1.second) == Seq("sqc8b49rny0", "sqdtr74hyu0"))
    }*/

    "client list" in {
      Await.result(client.clientList().map(x => warn(x)), 1.second)
    }

    "ping" in {
      Await.result(client.ping(), 1.second)
    }

    "keys" in {
      assert(Await.result(client.keys("*")).length > 0, "no more keys left before flush?")
    }

    "client list2" in {
      Await.result(client.clientList().map(x => warn(x)), 1.second)
    }

    "move and select" in {
      assert(Await.result(client.move("key1", 1), 5.second), "could not move Sicily to database 1")
      Await.result(client.select(1), 1.second)
      assert(Await.result(client.move("key1", 0), 5.second), "could not move Sicily to database 0")
      Await.result(client.select(0), 1.second)
    }

    "flushDB" in {
      Await.result(client.flushDB())
    }

    "disconnect" in {
      Await.result(client.close())
    }

  }

}

