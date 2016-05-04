package io.wasted.util
package redis

import java.util.concurrent.TimeUnit

import com.twitter.concurrent.{Broker, Offer}
import com.twitter.conversions.time._
import com.twitter.util._
import io.netty.buffer.ByteBufUtil
import io.netty.channel._
import io.netty.handler.codec.redis._
import io.netty.util.{CharsetUtil, ReferenceCountUtil}

import scala.collection.JavaConverters._

/**
 * String Channel
  *
  * @param out Outbound Broker
 * @param in Inbound Offer
 * @param channel Netty Channel
 */
final case class NettyRedisChannel(out: Broker[RedisMessage], in: Offer[RedisMessage], private val channel: Channel) extends Logger {
  def close(): Future[Unit] = {
    val closed = Promise[Unit]()
    send("quit")
    channel.closeFuture().addListener(new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = {
        closed.setDone()
      }
    })
    closed.raiseWithin(Duration(5, TimeUnit.SECONDS))(WheelTimer.twitter)
  }

  val onDisconnect = Promise[Unit]()
  channel.closeFuture().addListener(new ChannelFutureListener {
    override def operationComplete(f: ChannelFuture): Unit = onDisconnect.setDone()
  })

  def send[R <: RedisMessage](key: String, values: Seq[String]): Future[R] = send(List(key) ++ values: _*)
  def send[R <: RedisMessage](str: String*): Future[R] = {
    val cmds = str.map(s => new FullBulkStringRedisMessage(ByteBufUtil.writeUtf8(channel.alloc(), s)).asInstanceOf[RedisMessage])
    val msg = new ArrayRedisMessage(cmds.toList.asJava)
    out ! msg
    in.sync().flatMap {
        case a: ErrorRedisMessage =>
          val f = Future.exception(new Exception(a.toString))
          ReferenceCountUtil.release(a)
          f
        case o: RedisMessage if o.isInstanceOf[R] =>
          Future.value(ReferenceCountUtil.retain(o.asInstanceOf[R]))
        case o =>
          ReferenceCountUtil.release(o)
          Future.exception(new Exception("Response of type mismatch, got %s.".format(o.getClass.getSimpleName)))
    }
  }

  // admin commands
  def clientList(): Future[FullBulkStringRedisMessage] = send("client", "list")


  private def int(f: Future[IntegerRedisMessage]): Future[Long] = f.map(_.value())
  private def int2bool(f: Future[IntegerRedisMessage]): Future[Boolean] = f.map(_.value() > 0)

  private def str(f: Future[SimpleStringRedisMessage]): Future[String] = f.map(_.content())

  private def unit(f: Future[SimpleStringRedisMessage]): Future[Unit] = f.flatMap(x => Future.Done)

  private def bstrarrmap(keys: Seq[String], f: Future[ArrayRedisMessage]): Future[Map[String, String]] = f.map { arm =>
    keys.zipWithIndex.map { case (key, index) =>
      val value = arm.children().get(index).asInstanceOf[FullBulkStringRedisMessage]
        val e = key -> value.content().toString(CharsetUtil.UTF_8)
      value.release()
        e
    }.toMap
  }

  private def bstrmap(f: Future[ArrayRedisMessage]): Future[Map[String, String]] = f.map { arm =>
    arm.children().asScala.grouped(2).map { group =>
      val key = group(0).asInstanceOf[FullBulkStringRedisMessage]
      val value = group(1).asInstanceOf[FullBulkStringRedisMessage]
        val e = key.content().toString(CharsetUtil.UTF_8) -> value.content().toString(CharsetUtil.UTF_8)
      value.release()
      key.release()
        e
    }.toMap
  }

  private def barray(f: Future[ArrayRedisMessage]): Future[Seq[String]] = f.map { arm =>
    arm.children().asScala.map { strM =>
    val value = strM.asInstanceOf[FullBulkStringRedisMessage]
      val s = value.content().toString(CharsetUtil.UTF_8)
    value.release()
      s
    }
  }

  private def bstr(f: Future[FullBulkStringRedisMessage]): Future[String] = f.map { fbsrm =>
    val s = fbsrm.content().toString(CharsetUtil.UTF_8)
    fbsrm.release()
    s
  }

  private def bstr2float(f: Future[FullBulkStringRedisMessage]): Future[Float] = f.map { fbsrm =>
    val s = fbsrm.content().toString(CharsetUtil.UTF_8)
    fbsrm.release()
    s.toFloat
  }

  def set(key: String, value: String): Future[Unit] = unit(send("set", key, value))
  def get(key: String): Future[String] = bstr(send[FullBulkStringRedisMessage]("get", key))
  def del(key: String): Future[Long] = int(send("del", key))
  def del(key: Seq[String]): Future[Long] = int(send("del", key))

  def setEx(key: String, value: String, ttl: Long): Future[Unit] = unit(send("setex", Seq(key, ttl.toString, value)))
  def setEx(key: String, ttl: Long, value: String): Future[Unit] = unit(send("setex", Seq(key, ttl.toString, value)))
  def setNx(key: String, value: String): Future[Boolean] = int2bool(send("setnx", key, value))
  def getSet(key: String, value: String): Future[String] = bstr(send("getset", key, value))

  def multi(key: String): Future[Unit] = unit(send("multi", key))
  def discard(key: String): Future[Unit] = unit(send("discard", key))

  def append(key: String, value: String): Future[Long] = int(send[IntegerRedisMessage]("append", key, value))

  //def auth(key: String, value: String): Future[IntegerRedisMessage] = send("auth", key, value)
  //def bgrewriteaof(key: String, value: String): Future[IntegerRedisMessage] = send("bgrewriteaof", key, value)
  //def bgsave(key: String, value: String): Future[IntegerRedisMessage] = send("bgsave", key, value)
  //def bitcount(key: String, value: String): Future[IntegerRedisMessage] = send("bitcount", key, value)
//def bitop(key: String, value: String): Future[IntegerRedisMessage] = send("bitop", key, value)
//def bitpos(key: String, value: String): Future[IntegerRedisMessage] = send("bitpos", key, value)
//def blpop(key: String, value: String): Future[IntegerRedisMessage] = send("blpop", key, value)
//def brpop(key: String, value: String): Future[IntegerRedisMessage] = send("brpop", key, value)
//def brpoplpush(key: String, value: String): Future[IntegerRedisMessage] = send("brpoplpush", key, value)
//def command(key: String, value: String): Future[IntegerRedisMessage] = send("command", key, value)
  def dbSize(): Future[Long] = int(send("dbsize"))


  def incr(key: String): Future[Long] = int(send[IntegerRedisMessage]("incr", key))
  def incrBy(key: String, value: Long): Future[Long] = int(send("incrby", key, value.toString))
  def incrByFloat(key: String, value: Float): Future[Float] = bstr2float(send("incrbyfloat", key, value.toString))

  def decr(key: String): Future[Long] = int(send("decr", key))
  def decrBy(key: String, value: Long): Future[Long] = int(send("decrby", key, value.toString))

  //def dump(key: String): Future[Array[Byte]] = send("dump", key)
  def echo(key: String): Future[String] = bstr(send("echo", key))

  def eval(script: String, numKeys: Int, more: Seq[String]): Future[IntegerRedisMessage] = send(Seq("eval", script, numKeys.toString) ++ more: _*)
  //def evalsha(key: String, value: String): Future[IntegerRedisMessage] = send("evalsha", key, value)
  //def exec(key: String, value: String): Future[IntegerRedisMessage] = send("exec", key, value)
  def exists(key: String): Future[Boolean] = int2bool(send("exists", key))
  def expire(key: String, seconds: Int): Future[Boolean] = int2bool(send("expire", key, seconds.toString))
  def expire(key: String, duration: Duration): Future[Boolean] = int2bool(send("expire", key, duration.toSeconds.toString))
  def ttl(key: String): Future[Long] = int(send("ttl", key))

  def expireAt(key: String, at: Long): Future[Boolean] = int2bool(send("expireat", key, at.toString))
  def expireAt(key: String, at: java.util.Date): Future[Boolean] = expireAt(key, at.getTime)

  def flushAll(): Future[Unit] = unit(send("flushall"))
  def flushDB(): Future[Unit] = unit(send("flushdb"))

  def hMSet(key: String, values: Map[String, String]): Future[Unit] = unit(send("hmset", Seq(key)++ values.flatMap(x => List(x._1, x._2))))
  def hMGet(key: String, fields: Seq[String]): Future[Map[String, String]] = bstrarrmap(fields, send("hmget", Seq(key) ++ fields))

  def hSet(key: String, field: String, value: String): Future[Boolean] = int2bool(send("hset", key, field, value))
  def hGet(key: String, field: String): Future[String] = bstr(send("hget", key, field))
  def hGetAll(key: String): Future[Map[String, String]] = bstrmap(send("hgetall", key))
  def hKeys(key: String): Future[Seq[String]] = barray(send("hkeys", key))
  def hVals(key: String): Future[Seq[String]] = barray(send("hvals", key))
  def hDel(key: String, field: String): Future[Boolean] = int2bool(send("hdel", Seq(key, field)))
  def hDel(key: String, fields: Seq[String]): Future[Boolean] = int2bool(send("hdel", Seq(key) ++ fields))
  def hExists(key: String, field: String): Future[Boolean] = int2bool(send("hexists", key, field))
  def hIncrBy(key: String, field: String, value: Long): Future[Long] = int(send("hincrby", key, field, value.toString))
  def hIncrByFloat(key: String, field: String, value: Float): Future[Float] = bstr2float(send("hincrbyfloat", key, field, value.toString))
  def hLen(key: String): Future[Long] = int(send("hlen", key))
  def hSetNx(key: String, field: String, value: String): Future[Boolean] = int2bool(send("hsetnx", key, field, value))
  def hStrLen(key: String, field: String): Future[Long] = int(send("hstrlen", key, field))
  //def hScan(key: String, value: String): Future[IntegerRedisMessage] = send("hscan", key, value)

  def mGet(keys: Seq[String]): Future[Map[String, String]] = bstrarrmap(keys, send("mget", keys))
  def mSet(map: Map[String, String]): Future[Unit] = unit(send("mset", map.flatMap(x => List(x._1, x._2)).toSeq))
  def mSetNx(map: Map[String, String]): Future[Boolean] = int2bool(send("msetnx", map.flatMap(x => List(x._1, x._2)).toSeq))

  def sAdd(key: String, members: Seq[String]): Future[Int] = int(send("sadd", Seq(key) ++ members)).map(_.toInt)
  def sCard(key: String): Future[Long] = int(send("scard", key))
  def sDiff(key: String, key2: String): Future[Seq[String]] = barray(send("sdiff", Seq(key, key2)))
  def sDiff(key: String, keys: Seq[String]): Future[Seq[String]] = barray(send("sdiff", Seq(key) ++ keys))
  def sDiffStore(destination: String, key: String, keys: Seq[String]): Future[Long] = int(send("sdiffstore", Seq(destination, key) ++ keys))
  def sInter(key: String, key2: String): Future[Seq[String]] = barray(send("sinter", Seq(key, key2)))
  def sInter(key: String, keys: Seq[String]): Future[Seq[String]] = barray(send("sinter", Seq(key) ++ keys))
  def sInterStore(destination: String, key: String, keys: Seq[String]): Future[Long] = int(send("sinterstore", Seq(destination, key) ++ keys))
  def sIsMember(key: String, member: String): Future[Boolean] = int2bool(send("sismember", key, member))
  def sMembers(key: String): Future[Seq[String]] = barray(send("smembers", key))
  def sMove(source: String, destination: String, key: String): Future[Boolean] = int2bool(send("smove", source, destination, key))
  def sPop(key: String): Future[String] = bstr(send("spop", key))
  def sRandMember(key: String): Future[String] = bstr(send("srandmember", key))
  def sRem(key: String, members: Seq[String]): Future[Int] = int(send("srem", Seq(key) ++ members)).map(_.toInt)
  def sUnion(key: String, keys: Seq[String]): Future[Seq[String]] = barray(send("sunion", Seq(key) ++ keys))
  def sUnion(key: String, key2: String): Future[Seq[String]] = barray(send("sunion", Seq(key, key2)))
  def sUnionStore(destination: String, keys: Seq[String]): Future[Long] = int(send("sunionstore", Seq(destination) ++ keys))
  //def sScan(key: String, value: String): Future[IntegerRedisMessage] = send("sscan", key, value)


  def zAdd(key: String, value: String): Future[IntegerRedisMessage] = send("zadd", key, value)
  def zCard(key: String, value: String): Future[IntegerRedisMessage] = send("zcard", key, value)
  def zCount(key: String, value: String): Future[IntegerRedisMessage] = send("zcount", key, value)
  def zIncrBy(key: String, value: String): Future[IntegerRedisMessage] = send("zincrby", key, value)
  def zInterStore(key: String, value: String): Future[IntegerRedisMessage] = send("zinterstore", key, value)
  def zLexCount(key: String, value: String): Future[IntegerRedisMessage] = send("zlexcount", key, value)
  def zRange(key: String, value: String): Future[IntegerRedisMessage] = send("zrange", key, value)
  def zRangeByLex(key: String, value: String): Future[IntegerRedisMessage] = send("zrangebylex", key, value)
  def zRevRangeByLex(key: String, value: String): Future[IntegerRedisMessage] = send("zrevrangebylex", key, value)
  def zRangeByScore(key: String, value: String): Future[IntegerRedisMessage] = send("zrangebyscore", key, value)
  def zRank(key: String, value: String): Future[IntegerRedisMessage] = send("zrank", key, value)
  def zRem(key: String, value: String): Future[IntegerRedisMessage] = send("zrem", key, value)
  def zRemRangeByLex(key: String, value: String): Future[IntegerRedisMessage] = send("zremrangebylex", key, value)
  def zRemRangeByRank(key: String, value: String): Future[IntegerRedisMessage] = send("zremrangebyrank", key, value)
  def zRemRangeByScore(key: String, value: String): Future[IntegerRedisMessage] = send("zremrangebyscore", key, value)
  def zRevRange(key: String, value: String): Future[IntegerRedisMessage] = send("zrevrange", key, value)
  def zRevRangeByScore(key: String, value: String): Future[IntegerRedisMessage] = send("zrevrangebyscore", key, value)
  def zRevRank(key: String, value: String): Future[IntegerRedisMessage] = send("zrevrank", key, value)
  def zScore(key: String, value: String): Future[IntegerRedisMessage] = send("zscore", key, value)
  def zUnionStore(key: String, value: String): Future[IntegerRedisMessage] = send("zunionstore", key, value)
  def zScan(key: String, value: String): Future[IntegerRedisMessage] = send("zscan", key, value)


  def lIndex(key: String, value: String): Future[IntegerRedisMessage] = send("lindex", key, value)
  def lInsert(key: String, value: String): Future[IntegerRedisMessage] = send("linsert", key, value)
  def lLen(key: String, value: String): Future[IntegerRedisMessage] = send("llen", key, value)
  def lPop(key: String, value: String): Future[IntegerRedisMessage] = send("lpop", key, value)
  def lPush(key: String, value: String): Future[IntegerRedisMessage] = send("lpush", key, value)
  def lPusHx(key: String, value: String): Future[IntegerRedisMessage] = send("lpushx", key, value)
  def lRange(key: String, value: String): Future[IntegerRedisMessage] = send("lrange", key, value)
  def lRem(key: String, value: String): Future[IntegerRedisMessage] = send("lrem", key, value)
  def lSet(key: String, value: String): Future[IntegerRedisMessage] = send("lset", key, value)
  def lTrim(key: String, value: String): Future[IntegerRedisMessage] = send("ltrim", key, value)


  def rPop(key: String, value: String): Future[IntegerRedisMessage] = send("rpop", key, value)
  def rPoplPush(key: String, value: String): Future[IntegerRedisMessage] = send("rpoplpush", key, value)
  def rPush(key: String, value: String): Future[IntegerRedisMessage] = send("rpush", key, value)
  def rPushx(key: String, value: String): Future[IntegerRedisMessage] = send("rpushx", key, value)


  def pExpire(key: String, value: String): Future[IntegerRedisMessage] = send("pexpire", key, value)
  def pExpireAt(key: String, value: String): Future[IntegerRedisMessage] = send("pexpireat", key, value)
  def pfAdd(key: String, value: String): Future[IntegerRedisMessage] = send("pfadd", key, value)
  def pfCount(key: String, value: String): Future[IntegerRedisMessage] = send("pfcount", key, value)
  def pfMerge(key: String, value: String): Future[IntegerRedisMessage] = send("pfmerge", key, value)
  def ping(key: String, value: String): Future[IntegerRedisMessage] = send("ping", key, value)
  def pSetEx(key: String, value: String): Future[IntegerRedisMessage] = send("psetex", key, value)
  def pSubscribe(key: String, value: String): Future[IntegerRedisMessage] = send("psubscribe", key, value)
  def pUnsubscribe(key: String, value: String): Future[IntegerRedisMessage] = send("punsubscribe", key, value)
  def pTtl(key: String, value: String): Future[IntegerRedisMessage] = send("pttl", key, value)


  def geoAdd(key: String, value: String): Future[IntegerRedisMessage] = send("geoadd", key, value)
  def geoHash(key: String, value: String): Future[IntegerRedisMessage] = send("geohash", key, value)
  def geoPos(key: String, value: String): Future[IntegerRedisMessage] = send("geopos", key, value)
  def geoDist(key: String, value: String): Future[IntegerRedisMessage] = send("geodist", key, value)
  def geoRadius(key: String, value: String): Future[IntegerRedisMessage] = send("georadius", key, value)
  def geoRadiusByMember(key: String, value: String): Future[IntegerRedisMessage] = send("georadiusbymember", key, value)


  def setBit(key: String, value: String): Future[IntegerRedisMessage] = send("setbit", key, value)
  def setRange(key: String, value: String): Future[IntegerRedisMessage] = send("setrange", key, value)
  def getBit(key: String, value: String): Future[IntegerRedisMessage] = send("getbit", key, value)
  def getRange(key: String, value: String): Future[IntegerRedisMessage] = send("getrange", key, value)

  def info(key: String, value: String): Future[IntegerRedisMessage] = send("info", key, value)
  def keys(key: String, value: String): Future[IntegerRedisMessage] = send("keys", key, value)
  def lastSave(key: String, value: String): Future[IntegerRedisMessage] = send("lastsave", key, value)
  def migrate(key: String, value: String): Future[IntegerRedisMessage] = send("migrate", key, value)
  def monitor(key: String, value: String): Future[IntegerRedisMessage] = send("monitor", key, value)
  def move(key: String, value: String): Future[IntegerRedisMessage] = send("move", key, value)
  def `object`(key: String, value: String): Future[IntegerRedisMessage] = send("object", key, value)
  def persist(key: String, value: String): Future[IntegerRedisMessage] = send("persist", key, value)
  def pubsub(key: String, value: String): Future[IntegerRedisMessage] = send("pubsub", key, value)
  def publish(key: String, value: String): Future[IntegerRedisMessage] = send("publish", key, value)
  def randomKey(key: String, value: String): Future[IntegerRedisMessage] = send("randomkey", key, value)
  def readOnly(key: String, value: String): Future[IntegerRedisMessage] = send("readonly", key, value)
  def readWrite(key: String, value: String): Future[IntegerRedisMessage] = send("readwrite", key, value)
  def rename(key: String, value: String): Future[IntegerRedisMessage] = send("rename", key, value)
  def renameNx(key: String, value: String): Future[IntegerRedisMessage] = send("renamenx", key, value)
  def restore(key: String, value: String): Future[IntegerRedisMessage] = send("restore", key, value)
  def role(key: String, value: String): Future[IntegerRedisMessage] = send("role", key, value)
  def save(key: String, value: String): Future[IntegerRedisMessage] = send("save", key, value)
  def select(key: String, value: String): Future[IntegerRedisMessage] = send("select", key, value)
  def shutdown(key: String, value: String): Future[IntegerRedisMessage] = send("shutdown", key, value)
  def slaveOf(key: String, value: String): Future[IntegerRedisMessage] = send("slaveof", key, value)
  def slowlog(key: String, value: String): Future[IntegerRedisMessage] = send("slowlog", key, value)
  def sort(key: String, value: String): Future[IntegerRedisMessage] = send("sort", key, value)
  def strLen(key: String, value: String): Future[IntegerRedisMessage] = send("strlen", key, value)
  def subscribe(key: String, value: String): Future[IntegerRedisMessage] = send("subscribe", key, value)
  def sync(key: String, value: String): Future[IntegerRedisMessage] = send("sync", key, value)
  def time(key: String, value: String): Future[IntegerRedisMessage] = send("time", key, value)
  def `type`(key: String, value: String): Future[IntegerRedisMessage] = send("type", key, value)
  def unsubscribe(key: String, value: String): Future[IntegerRedisMessage] = send("unsubscribe", key, value)
  def unwatch(key: String, value: String): Future[IntegerRedisMessage] = send("unwatch", key, value)
  def wait(key: String, value: String): Future[IntegerRedisMessage] = send("wait", key, value)
  def watch(key: String, value: String): Future[IntegerRedisMessage] = send("watch", key, value)
  def scan(key: String, value: String): Future[IntegerRedisMessage] = send("scan", key, value)

}

/**
 * wasted.io Scala Redis Codec
 *
 * For composition you may use NettyRedisCodec()...
 */
final case class NettyRedisCodec()
  extends NettyCodec[java.net.URI, NettyRedisChannel] {
  val readTimeout: Option[Duration] = None
  val writeTimeout: Option[Duration] = None

  /**
   * Sets up basic Server-Pipeline for this Codec
    *
    * @param channel Channel to apply the Pipeline to
   */
  def serverPipeline(channel: Channel): Unit = throw new NotImplementedError("Redis Server Codec not implemented")

  /**
   * Sets up basic Redis Pipeline
    *
    * @param channel Channel to apply the Pipeline to
   */
  def clientPipeline(channel: Channel): Unit = {
    val p = channel.pipeline()
    p.addLast(new RedisDecoder())
    p.addLast(new RedisBulkStringAggregator())
    p.addLast(new RedisArrayAggregator())
    p.addLast(new RedisEncoder())
  }

  /**
   * Handle the connected channel and send the request
    *
    * @param channel Channel we're connected to
   * @param uri URI We want to use
   * @return
   */
  def clientConnected(channel: Channel, uri: java.net.URI): Future[NettyRedisChannel] = {
    val inBroker = new Broker[RedisMessage]
    val outBroker = new Broker[RedisMessage]

    channel.pipeline().addLast("redisHandler", new SimpleChannelInboundHandler[RedisMessage] {
      override def channelRead0(ctx: ChannelHandlerContext, msg: RedisMessage): Unit = {
        // we wire the inbound packet to the Broker
        inBroker ! msg
      }
    })

    // don't overflow the server immediately after handshake
    implicit val timer = WheelTimer
    Schedule(() => {
      // we wire the outbound broker to send to the channel
      outBroker.recv.foreach(buf => channel.writeAndFlush(buf))
    }, 50.millis)

    Future.value(NettyRedisChannel(outBroker, inBroker.recv, channel))
  }
}
