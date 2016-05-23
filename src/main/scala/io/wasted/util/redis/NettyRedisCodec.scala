package io.wasted.util
package redis

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import com.twitter.concurrent.{Broker, Offer}
import com.twitter.conversions.time._
import com.twitter.util._
import io.netty.buffer.{ByteBufUtil, PooledByteBufAllocator}
import io.netty.channel._
import io.netty.handler.codec.redis._
import io.netty.util.{CharsetUtil, ReferenceCountUtil}

import scala.collection.JavaConverters._


private[redis] final case class RedisAction[R <: RedisMessage](p: Promise[R], msg: ArrayRedisMessage)

/**
  * String Channel
  *
  * @param out Outbound Broker
  * @param in Inbound Offer
  */
final case class NettyRedisChannel(out: Broker[RedisMessage], in: Offer[RedisMessage], private val codec: NettyRedisCodec) extends Logger {
  private[this] val stopping = new AtomicBoolean(false)

  private[this] val allocator = PooledByteBufAllocator.DEFAULT
  private[this] var channel: Option[Channel] = None
  private[this] var client: Option[RedisClient] = None

  implicit val timer = WheelTimer

  private[redis] def setClient(cl: RedisClient): Unit = synchronized {
    client = Some(cl)
  }

  private[redis] def setChannel(chan: Channel): Unit = synchronized {
    if (chan.isActive && chan.isOpen && chan.isWritable) {
      chan.closeFuture().addListener(new ChannelFutureListener {
        override def operationComplete(f: ChannelFuture): Unit = if (!stopping.get) {
          Schedule.once(() => client.map(_.connect()), 1.second)
        }
      })
      channel = Some(chan)
    }
    else error("Bad channel set! Active: %s, Open: %s, Writable: %s", chan.isActive, chan.isOpen, chan.isWritable)
  }

  def isConnected(): Boolean = channel.exists(chan => chan.isActive && chan.isOpen && chan.isWritable)

  def close(): Future[Unit] = if (!isConnected()) Future.Done else {
    stopping.set(true)
    val closed = Promise[Unit]()
    send("quit")
    channel.map(_.closeFuture().addListener(new ChannelFutureListener {
      override def operationComplete(f: ChannelFuture): Unit = {
        closed.setDone()
      }
    })).getOrElse(closed.setDone())
    closed.raiseWithin(Duration(5, TimeUnit.SECONDS))(WheelTimer.twitter)
  }
  def timeout = client.flatMap(_.requestTimeout).getOrElse(5.seconds)

  private lazy val queue = new Wactor() {
    override protected def receive: PartialFunction[Any, Any] = {
      case RedisAction(p: Promise[RedisMessage], msg: ArrayRedisMessage) if isConnected() =>
        out ! msg
        try {
          p.setValue(Await.result(in.sync(), timeout))
        } catch {
          case t: Throwable => p.setException(t)
        }
      case redisAction: RedisAction[_] =>
        Schedule.once(() => this ! redisAction, 1.second)
      case x => warn("Got unwanted " + x.getClass)
    }
  }


  def send[R <: RedisMessage](key: String, values: Seq[String]): Future[R] = send(List(key) ++ values: _*)
  def send[R <: RedisMessage](str: String*): Future[R] = {
    val cmds = str.map(s => new FullBulkStringRedisMessage(ByteBufUtil.writeUtf8(allocator, s)).asInstanceOf[RedisMessage])
    val msg = new ArrayRedisMessage(cmds.toList.asJava)
    val p = Promise[R]
    queue ! RedisAction(p, msg)
    p.flatMap {
      case a: ErrorRedisMessage =>
        val f = Future.exception(new Exception(a.toString))
        ReferenceCountUtil.release(a)
        f
      case o: RedisMessage if o.isInstanceOf[R] =>
        Future.value(o.asInstanceOf[R])
      case o =>
        ReferenceCountUtil.release(o)
        Future.exception(new Exception("Response of type mismatch, got %s.".format(o.getClass.getSimpleName)))
    }
  }

  // admin commands
  def clientList(): Future[String] = bstr(send("client", "list"))


  private def int(f: Future[IntegerRedisMessage]): Future[Long] = {
    f.map(_.value())
  }.ensure(f.map(ReferenceCountUtil.release(_)))

  private def int2bool(f: Future[IntegerRedisMessage]): Future[Boolean] = {
    f.map(_.value() > 0)
  }.ensure(f.map(ReferenceCountUtil.release(_)))

  private def str(f: Future[SimpleStringRedisMessage]): Future[String] = {
    f.map(_.content())
  }.ensure(f.map(ReferenceCountUtil.release(_)))

  private def unit(f: Future[SimpleStringRedisMessage]): Future[Unit] = f.flatMap { x =>
    ReferenceCountUtil.release(x)
    Future.Done
  }

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
    fbsrm.content().toString(CharsetUtil.UTF_8)
  }.ensure(f.map(ReferenceCountUtil.release(_)))

  private def bstr2float(f: Future[FullBulkStringRedisMessage]): Future[Float] = f.map { fbsrm =>
    fbsrm.content().toString(CharsetUtil.UTF_8).toFloat
  }.ensure(f.map(ReferenceCountUtil.release(_)))


  //def auth(password: String): Future[Unit] = unit(send("auth", password))
  def bgrewriteaof(): Future[Unit] = {
    unit(send("bgrewriteaof"))
  }
  def bgsave(): Future[Unit] = {
    unit(send("bgsave"))
  }
  def dbSize(): Future[Long] = {
    int(send("dbsize"))
  }
  def info(section: Option[String] = None): Future[Seq[String]] = {
    barray(send("info", section.toSeq))
  }
  def ping(): Future[Unit] = {
    str(send("ping")).filter(_ == "PONG").map(_ => ())
  }
  def ping(message: String): Future[Unit] = {
    unit(send("ping", message)).filter(_ == message).map(_ => ())
  }

  def keys(pattern: String): Future[Seq[String]] = {
    barray(send("keys", pattern))
  }
  def lastSave(): Future[Long] = {
    int(send("lastsave"))
  }
  def move(key: String, database: Int): Future[Boolean] = {
    int2bool(send("move", key, database.toString))
  }
  def persist(key: String): Future[Boolean] = {
    int2bool(send("persist", key))
  }
  def randomKey(): Future[Option[String]] = {
    bstr(send("randomkey")).flatMap {
      case res if res.nonEmpty => Future.value(Some(res))
      case res => Future.value(None)
    }
  }
  def readOnly(): Future[Unit] = {
    unit(send("readonly"))
  }
  def readWrite(): Future[Unit] = {
    unit(send("readwrite"))
  }
  def rename(oldKey: String, newKey: String): Future[Unit] = {
    unit(send("rename", oldKey, newKey))
  }
  def renameNx(oldKey: String, newKey: String): Future[Boolean] = {
    int2bool(send("renamenx", oldKey, newKey))
  }

  def save(): Future[Unit] = {
    unit(send("save"))
  }
  def select(database: Int): Future[Unit] = {
    unit(send("select", database.toString))
  }

  def shutdown(): Future[Unit] = {
    unit(send("shutdown"))
  }
  def shutdown(save: Boolean): Future[Unit] = {
    unit(send("shutdown", if (save) "save" else "nosave"))
  }
  def slaveOf(host: String, port: Int): Future[IntegerRedisMessage] = {
    send("slaveof", host, port.toString)
  }
  def slaveOf(host: String, port: Option[Int] = None): Future[IntegerRedisMessage] = {
    send("slaveof", host, port.getOrElse(6379).toString)
  }

  def strLen(key: String): Future[Long] = {
    int(send("strlen", key))
  }
  def sync(): Future[Unit] = {
    unit(send("sync"))
  }
  def time(): Future[(Long,Int)] = {
    barray(send("time")).map {
      case s if s.length == 2 => s(0).toLong -> s(1).toInt
      case s => throw new IllegalArgumentException("Did not return two valid numbers")
    }
  }
  def `type`(key: String): Future[String] = {
    str(send("type", key))
  }

  def wait(numSlaves: Int, millis: Long): Future[Int] = {
    int(send("wait", numSlaves.toString, millis.toString)).map(_.toInt)
  }

  def set(key: String, value: String): Future[Unit] = {
    unit(send("set", key, value))
  }
  def get(key: String): Future[Option[String]] = {
    bstr(send[FullBulkStringRedisMessage]("get", key)).flatMap {
      case res if res.nonEmpty => Future.value(Some(res))
      case res => Future.value(None)
    }.rescue {
      case t: Throwable => Future.value(None)
    }
  }
  def del(key: String): Future[Long] = {
    int(send("del", key))
  }
  def del(key: Seq[String]): Future[Long] = {
    int(send("del", key))
  }

  def setEx(key: String, value: String, seconds: Long): Future[Unit] = {
    unit(send("setex", Seq(key, seconds.toString, value)))
  }
  def setEx(key: String, seconds: Long, value: String): Future[Unit] = {
    unit(send("setex", Seq(key, seconds.toString, value)))
  }
  def setNx(key: String, value: String): Future[Boolean] = {
    int2bool(send("setnx", key, value))
  }
  def getSet(key: String, value: String): Future[String] = {
    bstr(send("getset", key, value))
  }

  def append(key: String, value: String): Future[Long] = {
    int(send[IntegerRedisMessage]("append", key, value))
  }

  def setBit(key: String, offset: Long, bit: Boolean): Future[Long] = {
    int(send("setbit", key, offset.toString, if (bit) "1" else "0"))
  }
  def setRange(key: String, offset: Long, value: String): Future[Long] = {
    int(send("setrange", key, offset.toString, value))
  }
  def getBit(key: String, offset: Long): Future[Boolean] = {
    int2bool(send("getbit", key, offset.toString))
  }
  def getRange(key: String, start: Long, offset: Long): Future[String] = {
    bstr(send("getrange", key, start.toString, offset.toString))
  }
  def bitCount(key: String): Future[Long] = {
    int(send("bitcount", key))
  }
  def bitCount(key: String, start: Long, end: Long): Future[Long] = {
    int(send("bitcount", key, start.toString, end.toString))
  }
  def bitPos(key: String, bit: Boolean): Future[Long] = {
    bitPos(key, bit, None, None)
  }
  def bitPos(key: String, bit: Boolean, start: Long, end: Long): Future[Long] = {
    bitPos(key, bit, Some(start), Some(end))
  }
  def bitPos(key: String, bit: Boolean, start: Option[Long], end: Option[Long]): Future[Long] = {
    int(send("bitpos", Seq(key, if (bit) "1" else "0") ++ start.map(_.toString).toList ++ end.map(_.toString).toList))
  }

  def bitOp(destination: String, operation: RedisBitOperation.Value, source: String): Future[Long] = {
    int(send("bitop", operation.toString, destination, source))
  }
  def bitOp(destination: String, operation: RedisBitOperation.Value, sources: Seq[String]): Future[Long] = {
    assert(operation != RedisBitOperation.NOT, "BitOp with NOT and multiple sources is not allowed")
    int(send("bitop", Seq(operation.toString, destination) ++ sources))
  }

  def incr(key: String): Future[Long] = {
    int(send[IntegerRedisMessage]("incr", key))
  }
  def incrBy(key: String, value: Long): Future[Long] = {
    int(send("incrby", key, value.toString))
  }
  def incrByFloat(key: String, value: Float): Future[Float] = {
    bstr2float(send("incrbyfloat", key, value.toString))
  }

  def decr(key: String): Future[Long] = int(send("decr", key))
  def decrBy(key: String, value: Long): Future[Long] = {
    int(send("decrby", key, value.toString))
  }
  def echo(key: String): Future[String] = {
    bstr(send("echo", key))
  }

  def eval(script: String, numKeys: Int, more: Seq[String]): Future[IntegerRedisMessage] = {
    send(Seq("eval", script, numKeys.toString) ++ more: _*)
  }

  def exists(key: String): Future[Boolean] = {
    int2bool(send("exists", key))
  }
  def expire(key: String, seconds: Int): Future[Boolean] = {
    int2bool(send("expire", key, seconds.toString))
  }
  def expire(key: String, duration: Duration): Future[Boolean] = {
    int2bool(send("expire", key, duration.toSeconds.toString))
  }
  def ttl(key: String): Future[Long] = {
    int(send("ttl", key))
  }
  /*def scan(key: String, value: String): Future[IntegerRedisMessage] = {
    send("scan", key, value)
  }*/

  def expireAt(key: String, at: Long): Future[Boolean] = {
    int2bool(send("expireat", key, at.toString))
  }
  def expireAt(key: String, at: java.util.Date): Future[Boolean] = {
    expireAt(key, at.getTime)
  }

  def flushAll(): Future[Unit] = {
    unit(send("flushall"))
  }
  def flushDB(): Future[Unit] = {
    unit(send("flushdb"))
  }

  def hMSet(key: String, values: Map[String, String]): Future[Unit] = {
    unit(send("hmset", Seq(key)++ values.flatMap(x => List(x._1, x._2))))
  }
  def hMGet(key: String, fields: Seq[String]): Future[Map[String, String]] = {
    bstrarrmap(fields, send("hmget", Seq(key) ++ fields))
  }

  def hSet(key: String, field: String, value: String): Future[Boolean] = {
    int2bool(send("hset", key, field, value))
  }
  def hGet(key: String, field: String): Future[Option[String]] = {
    bstr(send("hget", key, field)).flatMap {
      case res if res.nonEmpty => Future.value(Some(res))
      case res => Future.value(None)
    }
  }
  def hGetAll(key: String): Future[Map[String, String]] = {
    bstrmap(send("hgetall", key))
  }
  def hKeys(key: String): Future[Seq[String]] = {
    barray(send("hkeys", key))
  }
  def hVals(key: String): Future[Seq[String]] = {
    barray(send("hvals", key))
  }
  def hDel(key: String, field: String): Future[Boolean] = {
    int2bool(send("hdel", Seq(key, field)))
  }
  def hDel(key: String, fields: Seq[String]): Future[Boolean] = {
    int2bool(send("hdel", Seq(key) ++ fields))
  }
  def hExists(key: String, field: String): Future[Boolean] = {
    int2bool(send("hexists", key, field))
  }
  def hIncrBy(key: String, field: String, value: Long): Future[Long] = {
    int(send("hincrby", key, field, value.toString))
  }
  def hIncrByFloat(key: String, field: String, value: Float): Future[Float] = {
    bstr2float(send("hincrbyfloat", key, field, value.toString))
  }
  def hLen(key: String): Future[Long] = {
    int(send("hlen", key))
  }
  def hSetNx(key: String, field: String, value: String): Future[Boolean] = {
    int2bool(send("hsetnx", key, field, value))
  }
  /*def hScan(key: String, value: String): Future[IntegerRedisMessage] = {
    send("hscan", key, value)
  }*/

  def mGet(keys: Seq[String]): Future[Map[String, String]] = {
    bstrarrmap(keys, send("mget", keys))
  }
  def mSet(map: Map[String, String]): Future[Unit] = {
    unit(send("mset", map.flatMap(x => List(x._1, x._2)).toSeq))
  }
  def mSetNx(map: Map[String, String]): Future[Boolean] = {
    int2bool(send("msetnx", map.flatMap(x => List(x._1, x._2)).toSeq))
  }

  def sAdd(key: String, member: String): Future[Int] = {
    int(send("sadd", key, member)).map(_.toInt)
  }
  def sAdd(key: String, members: Seq[String]): Future[Int] = {
    int(send("sadd", Seq(key) ++ members)).map(_.toInt)
  }
  def sCard(key: String): Future[Long] = {
    int(send("scard", key))
  }
  def sDiff(key: String, key2: String): Future[Seq[String]] = {
    barray(send("sdiff", key, key2))
  }
  def sDiff(key: String, keys: Seq[String]): Future[Seq[String]] = {
    barray(send("sdiff", Seq(key) ++ keys))
  }
  def sDiffStore(destination: String, key: String, keys: Seq[String]): Future[Long] = {
    int(send("sdiffstore", Seq(destination, key) ++ keys))
  }
  def sInter(key: String, key2: String): Future[Seq[String]] = {
    barray(send("sinter", Seq(key, key2)))
  }
  def sInter(key: String, keys: Seq[String]): Future[Seq[String]] = {
    barray(send("sinter", Seq(key) ++ keys))
  }
  def sInterStore(destination: String, key: String, keys: Seq[String]): Future[Long] = {
    int(send("sinterstore", Seq(destination, key) ++ keys))
  }
  def sIsMember(key: String, member: String): Future[Boolean] = {
    int2bool(send("sismember", key, member))
  }
  def sMembers(key: String): Future[Seq[String]] = {
    barray(send("smembers", key))
  }
  def sMove(source: String, destination: String, key: String): Future[Boolean] = {
    int2bool(send("smove", source, destination, key))
  }
  def sPop(key: String): Future[String] = {
    bstr(send("spop", key))
  }
  def sRandMember(key: String): Future[String] = {
    bstr(send("srandmember", key))
  }
  def sRem(key: String, member: String): Future[Int] = {
    int(send("srem", key, member)).map(_.toInt)
  }
  def sRem(key: String, members: Seq[String]): Future[Int] = {
    int(send("srem", Seq(key) ++ members)).map(_.toInt)
  }
  def sUnion(key: String, keys: Seq[String]): Future[Seq[String]] = {
    barray(send("sunion", Seq(key) ++ keys))
  }
  def sUnion(key: String, key2: String): Future[Seq[String]] = {
    barray(send("sunion", key, key2))
  }
  def sUnionStore(destination: String, keys: Seq[String]): Future[Long] = {
    int(send("sunionstore", Seq(destination) ++ keys))
  }
  /*def sScan(key: String, value: String): Future[IntegerRedisMessage] = {
    send("sscan", key, value)
  }*/

  def lIndex(key: String, index: Long): Future[Option[String]] = bstr(send("lindex", key, index.toString)).flatMap {
    case res if res.nonEmpty => Future.value(Some(res))
    case res => Future.value(None)
  }
  def lInsertBefore(key: String, before: String, value: String): Future[Long] = {
    int(send("linsert", key, "before", before, value))
  }
  def lInsertAfter(key: String, after: String, value: String): Future[Long] = {
    int(send("linsert", key, "after", after, value))
  }
  def lLen(key: String): Future[Long] = {
    int(send("llen", key))
  }
  def lPop(key: String): Future[Option[String]] = bstr(send("lpop", key)).flatMap {
    case res if res.nonEmpty => Future.value(Some(res))
    case res => Future.value(None)
  }
  def lPush(key: String, value: String): Future[Long] = {
    int(send("lpush", key, value))
  }
  def lPush(key: String, values: Seq[String]): Future[Long] = {
    int(send("lpush", Seq(key) ++ values))
  }
  def lPushX(key: String, value: String): Future[Long] = {
    int(send("lpushx", key, value))
  }
  def lRange(key: String, start: Long, stop: Long): Future[Seq[String]] = {
    barray(send("lrange", key, start.toString, stop.toString))
  }
  def lRem(key: String, count: Long, value: String): Future[Long] = {
    int(send("lrem", key, count.toString, value))
  }
  def lSet(key: String, index: Long, value: String): Future[Unit] = {
    unit(send("lset", key, index.toString, value))
  }
  def lTrim(key: String, start: Long, stop: Long): Future[Unit] = {
    unit(send("ltrim", key, start.toString, stop.toString))
  }

  def rPop(key: String): Future[Option[String]] = bstr(send("rpop", key)).flatMap {
    case res if res.nonEmpty => Future.value(Some(res))
    case res => Future.value(None)
  }
  def rPoplPush(source: String, destination: String): Future[String] = {
    bstr(send("rpoplpush", source, destination))
  }
  def rPush(key: String, value: String): Future[Long] = {
    int(send("rpush", key, value))
  }
  def rPush(key: String, values: Seq[String]): Future[Long] = {
    int(send("rpush", Seq(key) ++ values))
  }
  def rPushX(key: String, value: String): Future[Long] = {
    int(send("rpushx", key, value))
  }

  def pExpire(key: String, seconds: Int): Future[Boolean] = {
    int2bool(send("pexpire", key, seconds.toString))
  }
  def pExpire(key: String, duration: Duration): Future[Boolean] = {
    int2bool(send("pexpire", key, duration.toSeconds.toString))
  }
  def pExpireAt(key: String, at: Long): Future[Boolean] = {
    int2bool(send("pexpireat", key, at.toString))
  }
  def pExpireAt(key: String, at: java.util.Date): Future[Boolean] = {
    int2bool(send("pexpireat", key, at.getTime.toString))
  }
  def pSetEx(key: String, value: String, millis: Long): Future[Unit] = {
    unit(send("psetex", Seq(key, millis.toString, value)))
  }
  def pSetEx(key: String, millis: Long, value: String): Future[Unit] = {
    unit(send("psetex", Seq(key, millis.toString, value)))
  }
  def pTtl(key: String): Future[Long] = {
    int(send("pttl", key))
  }

  def pfAdd(key: String, element: String): Future[Boolean] = {
    int2bool(send("pfadd", key, element))
  }
  def pfAdd(key: String, elements: Seq[String]): Future[Boolean] = {
    int2bool(send("pfadd", Seq(key) ++ elements))
  }
  def pfCount(key: String): Future[Long] = {
    int(send("pfcount", key))
  }
  def pfCount(keys: Seq[String]): Future[Long] = {
    int(send("pfcount", keys))
  }
  def pfMerge(destination: String, source: String): Future[Unit] = {
    unit(send("pfmerge", destination, source))
  }
  def pfMerge(destination: String, sources: Seq[String]): Future[Unit] = {
    unit(send("pfmerge", Seq(destination) ++ sources))
  }

  def zAdd(key: String, score: Int, member: String): Future[Double] = {
    zAdd(key, score.toDouble, member, false)
  }
  def zAdd(key: String, score: Int, member: String, incr: Boolean): Future[Double] = {
    zAdd(key, score.toDouble, member, incr)
  }


  def zAdd(key: String, score: Double, member: String): Future[Double] = {
    zAdd(key, false, Seq(score -> member))
  }

  def zAdd(key: String, score: Double, member: String, incr: Boolean): Future[Double] = {
    zAdd(key, incr, Seq(score -> member))
  }

  def zAdd(key: String, members: Seq[(Double, String)]): Future[Double] = {
    zAdd(key, false, members)
  }

  def zAdd(key: String, incr: Boolean, members: Seq[(Double, String)]): Future[Double] = {
    val args = if (!incr) {
      List(key) ++ members.flatMap(x => x._1.toString :: x._2.toString :: Nil)
    } else {
      List(key, "INCR") ++ members.flatMap(x => x._1.toString :: x._2.toString :: Nil)
    }
    send[RedisMessage]("zadd", args).flatMap {
      case a: IntegerRedisMessage => int(Future.value(a)).map(_.toDouble)
      case a: FullBulkStringRedisMessage => bstr(Future.value(a)).map(_.toDouble)
      case x =>
        ReferenceCountUtil.release(x)
        Future.exception(new IllegalArgumentException("%s was not expected here" format x.getClass.getSimpleName))
    }
  }
  def zCard(key: String): Future[Long] = {
    int(send("zcard", key))
  }
  def zCount(key: String): Future[Long] = {
    int(send("zcount", key, "-inf", "+inf"))
  }
  def zCount(key: String, min: String, max: String): Future[Long] = {
    int(send("zcount", key, min, max))
  }
  def zIncrBy(key: String, score: Long, member: String): Future[Double] = {
    bstr(send("zincrby", key, score.toString, member.toString)).map(_.toDouble)
  }

  /*def zInterStore(key: String, value: String): Future[IntegerRedisMessage] = {
    send("zinterstore", key, value)
  }*/

  def zLexCount(key: String): Future[Long] = {
    int(send("zlexcount", key, "-", "+"))
  }
  def zLexCount(key: String, min: String, max: String): Future[Long] = {
    int(send("zlexcount", key, min, max))
  }
  def zRange(key: String, start: Long, stop: Long): Future[Seq[String]] = {
    barray(send("zrange", key, start.toString, stop.toString))
  }

  def zRangeByLex(key: String, start: String, stop: String): Future[Seq[String]] = {
    barray(send("zrangebylex", key, start, stop))
  }
  def zRangeByLex(key: String, start: String, stop: String, limit: Long, offset: Long, count: Long): Future[Seq[String]] = {
    barray(send("zrangebylex", key, start, stop, limit.toString, offset.toString, count.toString))
  }

  def zRangeByScore(key: String): Future[Seq[String]] = {
    barray(send("zrangebyscore", key, "-inf", "+inf"))
  }
  def zRangeByScore(key: String, min: String, max: String): Future[Seq[String]] = {
    barray(send("zrangebyscore", key, min, max))
  }
  def zRangeByScore(key: String, min: String, max: String, limit: Long, offset: Long, count: Long): Future[Seq[String]] = {
    barray(send("zrangebyscore", key, min, max, limit.toString, offset.toString, count.toString))
  }

  def zRank(key: String, member: String): Future[Long] = {
    send[RedisMessage]("zrank", key, member).flatMap {
      case a: IntegerRedisMessage => int(Future.value(a))
      case a: FullBulkStringRedisMessage =>
        ReferenceCountUtil.release(a)
        Future.exception(new IllegalArgumentException("Key does not exist in sorted set"))
      case x =>
        ReferenceCountUtil.release(x)
        Future.exception(new IllegalArgumentException("%s was not expected here" format x.getClass.getSimpleName))

    }
  }
  def zRem(key: String, member: String): Future[Long] = {
    int(send("zrem", key, member))
  }
  def zRem(key: String, members: Seq[String]): Future[Long] = {
    int(send("zrem", Seq(key) ++ members))
  }

  def zRemRangeByLex(key: String, min: String, max: String): Future[Long] = {
    int(send("zremrangebylex", key, min, max))
  }
  def zRemRangeByRank(key: String, start: Long, stop: Long): Future[Long] = {
    int(send("zremrangebyrank", key, start.toString, stop.toString))
  }
  def zRemRangeByScore(key: String, min: String, max: String): Future[Long] = {
    int(send("zremrangebyscore", key, min, max))
  }

  def zRevRange(key: String, start: Long, stop: Long): Future[Seq[String]] = {
    barray(send("zrevrange", key, start.toString, stop.toString))
  }

  def zRevRangeByLex(key: String, start: String, stop: String): Future[Seq[String]] = {
    barray(send("zrevrangebylex", key, start, stop))
  }
  def zRevRangeByLex(key: String, start: String, stop: String, limit: Long, offset: Long, count: Long): Future[Seq[String]] = {
    barray(send("zrevrangebylex", key, start, stop, limit.toString, offset.toString, count.toString))
  }

  def zRevRangeByScore(key: String): Future[Seq[String]] = {
    barray(send("zrevrangebyscore", key, "+inf", "-inf"))
  }
  def zRevRangeByScore(key: String, min: String, max: String): Future[Seq[String]] = {
    barray(send("zrevrangebyscore", key, min, max))
  }
  def zRevRangeByScore(key: String, min: String, max: String, limit: Long, offset: Long, count: Long): Future[Seq[String]] = {
    barray(send("zrevrangebyscore", key, min, max, limit.toString, offset.toString, count.toString))
  }

  def zRevRank(key: String, member: String): Future[Long] = {
    send[RedisMessage]("zrevrank", key, member).flatMap {
      case a: IntegerRedisMessage => int(Future.value(a))
      case a: FullBulkStringRedisMessage =>
        ReferenceCountUtil.release(a)
        Future.exception(new IllegalArgumentException("Key does not exist in sorted set"))
      case x =>
        ReferenceCountUtil.release(x)
        Future.exception(new IllegalArgumentException("%s was not expected here" format x.getClass.getSimpleName))

    }
  }

  def zScore(key: String, member: String): Future[Double] = {
    bstr(send("zscore", key, member)).map(_.toDouble)
  }
  /*def zUnionStore(key: String, value: String): Future[IntegerRedisMessage] = {
    send("zunionstore", key, value)
  }*/
  /*def zScan(key: String, value: String): Future[IntegerRedisMessage] = {
    send("zscan", key, value)
  }*/

  def geoAdd(key: String, objects: Iterable[RedisGeoObject]): Future[Int] = int(send("geoadd", Seq(key) ++ objects.flatMap(_.toRedis).toSeq)).map(_.toInt)
  def geoHash(key: String, member: String): Future[String] = barray(send("geohash", key, member)).map(_.head)
  def geoHash(key: String, members: Seq[String]): Future[Seq[String]] = barray(send("geohash", Seq(key) ++ members))
  def geoDist(key: String, member1: String, member2: String, unit: RedisDistanceUnit.Value): Future[Double] = bstr(send("geodist", key, member1, member2, unit.toString)).map(_.toDouble)
  //def geoRadius(key: String, value: String): Future[IntegerRedisMessage] = send("georadius", key, value)
  //def geoRadiusByMember(key: String, value: String): Future[IntegerRedisMessage] = send("georadiusbymember", key, value)
  //def geoPos(key: String, value: String): Future[IntegerRedisMessage] = send("geopos", key, value)


  /*
  def pSubscribe(key: String, value: String): Future[IntegerRedisMessage] = send("psubscribe", key, value)
  def publish(key: String, value: String): Future[IntegerRedisMessage] = send("publish", key, value)
  def pubsub(key: String, value: String): Future[IntegerRedisMessage] = send("pubsub", key, value)
  def pUnsubscribe(key: String, value: String): Future[IntegerRedisMessage] = send("punsubscribe", key, value)
  def subscribe(key: String, value: String): Future[IntegerRedisMessage] = send("subscribe", key, value)
  def unsubscribe(key: String, value: String): Future[IntegerRedisMessage] = send("unsubscribe", key, value)
  */


  /* We don't like blocking crap. use pubsub*/
  //def blPop(key: String, value: String): Future[IntegerRedisMessage] = send("blpop", key, value)
  //def brPop(key: String, value: String): Future[IntegerRedisMessage] = send("brpop", key, value)
  //def brPoplPush(key: String, value: String): Future[IntegerRedisMessage] = send("brpoplpush", key, value)

  /* Not sure how to handle queued up response from exec with our futures */
  //def multi(): Future[Unit] = unit(send("multi"))
  //def discard(): Future[Unit] = unit(send("discard"))
  //def exec(): Future[Seq[String]] = barray(send("exec"))
  //def watch(key: String): Future[Unit] = unit(send("watch", key))
  //def watch(keys: Seq[String]): Future[Unit] = unit(send("watch", keys))
  //def unwatch(): Future[Unit] = unit(send("unwatch"))

  //def dump(key: String): Future[Array[Byte]] = send("dump", key)
  //def hStrLen(key: String, field: String): Future[Long] = int(send("hstrlen", key, field))
  //def evalsha(key: String, value: String): Future[IntegerRedisMessage] = send("evalsha", key, value)
  //def slowlog(subcommand: String, argument: Option[String]): Future[IntegerRedisMessage] = send("slowlog", key, value)
  //def sort(key: String, value: String): Future[IntegerRedisMessage] = send("sort", key, value)
  //def restore(key: String, value: String): Future[IntegerRedisMessage] = send("restore", key, value)
  //def role(key: String, value: String): Future[IntegerRedisMessage] = send("role", key, value)
  //def command(key: String, value: String): Future[IntegerRedisMessage] = send("command", key, value)
  //def migrate(key: String, value: String): Future[IntegerRedisMessage] = send("migrate", key, value)
  //def monitor(key: String, value: String): Future[IntegerRedisMessage] = send("monitor", key, value)
  //def `object`(key: String, value: String): Future[IntegerRedisMessage] = send("object", key, value)

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
        inBroker ! ReferenceCountUtil.retain(msg)
      }

      override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
        cause.printStackTrace()
      }
    })

    // don't overflow the server immediately after handshake
    implicit val timer = WheelTimer
    Schedule(() => {
      // we wire the outbound broker to send to the channel
      outBroker.recv.foreach(buf => channel.writeAndFlush(buf))
    }, 50.millis)

    val redisChan = NettyRedisChannel(outBroker, inBroker.recv, this)
    redisChan.setChannel(channel)
    Future.value(redisChan)
  }
}


final case class RedisGeoObject(longitude: Double, latitude: Double, member: String) {
  private[redis] def toRedis = Seq(longitude.toString, latitude.toString, member)
}

object RedisBitOperation extends Enumeration {
  val AND = Value("and")
  val OR = Value("or")
  val XOR = Value("xor")
  val NOT = Value("not")
}

object RedisDistanceUnit extends Enumeration {
  val Meters = Value("m")
  val Kilometers = Value("km")
  val Miles = Value("mi")
  val Feet = Value("ft")
}