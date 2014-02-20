package io.wasted.util.apn

import io.netty.buffer._
import io.netty.util.CharsetUtil
import io.wasted.util.ssl.{ KeyStoreType, Ssl }
import java.nio.ByteOrder

/**
 * Apple Push Notification Item.
 *
 * @param deviceToken Apple Device Token as Hex-String
 * @param payload APN Payload
 * @param ident Transaction Identifier
 * @param expire Expiry
 */
case class Item(deviceToken: String, payload: String, prio: Int, ident: Int, expire: Option[java.util.Date] = None) {
  def bytes(position: Int): ByteBuf = {
    val payloadBuf = Unpooled.copiedBuffer(payload, CharsetUtil.UTF_8)
    val deviceTokenA: Array[Byte] = deviceToken.grouped(2).map(Integer.valueOf(_, 16).toByte).toArray

    val itemData = Unpooled.buffer(32 + 256 + 4 + 4 + 1).order(ByteOrder.BIG_ENDIAN)
    itemData.writeBytes(deviceTokenA)
    itemData.writeBytes(payloadBuf)
    itemData.writeInt(ident)
    itemData.writeInt(expire.map(_.getTime / 1000).getOrElse(0L).toInt) // expiration
    itemData.writeByte(prio.toByte) // prio

    val itemHeader = Unpooled.buffer(1 + 2).order(ByteOrder.BIG_ENDIAN)
    itemHeader.writeByte(position.toByte) // Item 0, since we don't queue
    itemHeader.writeShort(itemData.readableBytes.toShort)

    Unpooled.copiedBuffer(itemHeader, itemData.slice())
  }
}

/**
 * Apple Push Notification Push message.
 *
 * @param items List of Push Items to send
 */
case class Message(items: List[Item]) {
  lazy val bytes = {
    val tailBuffers = items.zipWithIndex.toList.map(x => x._1.bytes(x._2))
    val size = tailBuffers.map(_.readableBytes).sum
    val buf = Unpooled.buffer(1 + 4).order(ByteOrder.BIG_ENDIAN)
    buf.writeByte(2.toByte) // Command set version 2
    buf.writeInt(size)
    val buffers = List(buf) ++ tailBuffers
    Unpooled.copiedBuffer(buffers: _*)
  }
}

/**
 * Apple Push Notification connection parameters
 * @param name Name of this connection
 * @param p12 InputStream of the P12 Certificate
 * @param secret Secret for the P12 Certificate
 * @param sandbox Wether to use Sandbox or Production
 * @param timeout Connection timeout, default shouldb e fine
 */
case class Params(name: String, p12: java.io.InputStream, secret: String, sandbox: Boolean = false, timeout: Int = 5) {
  private lazy val context = Ssl.context(p12, secret, KeyStoreType.P12)
  def createSSLEngine(host: String, port: Int) = {
    val engine = context.createSSLEngine(host, port)
    engine.setNeedClientAuth(true)
    engine.setUseClientMode(true)
    engine
  }
}