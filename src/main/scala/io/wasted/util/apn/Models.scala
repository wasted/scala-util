package io.wasted.util.apn

import io.netty.buffer._
import io.netty.util.CharsetUtil
import io.wasted.util.ssl.{ KeyStoreType, Ssl }
import java.nio.ByteOrder

/**
 * Apple Push Notification Message.
 *
 * @param deviceToken Apple Device Token as Hex-String
 * @param payload APN Payload
 * @param ident Transaction Identifier
 * @param expire Expiry
 */
case class Message(deviceToken: String, payload: String, prio: Int, ident: Int = 10, expire: Option[java.util.Date] = None) {
  lazy val bytes: ByteBuf = {
    val payloadBuf = Unpooled.copiedBuffer(payload, CharsetUtil.UTF_8)
    val deviceTokenA: Array[Byte] = deviceToken.grouped(2).map(Integer.valueOf(_, 16).toByte).toArray

    // take 5 times the max-message length
    val bufData = Unpooled.buffer(5 * (3 + 32 + 256 + 4 + 4 + 1)).order(ByteOrder.BIG_ENDIAN)

    // frame data
    bufData.writeByte(1.toByte)
    bufData.writeShort(deviceTokenA.length)
    bufData.writeBytes(deviceTokenA)

    bufData.writeByte(2.toByte)
    bufData.writeShort(payloadBuf.readableBytes)
    bufData.writeBytes(payloadBuf)

    bufData.writeByte(3.toByte)
    bufData.writeShort(4)
    bufData.writeInt(ident)

    bufData.writeByte(4.toByte)
    bufData.writeShort(4)
    bufData.writeInt(expire.map(_.getTime / 1000).getOrElse(0L).toInt) // expiration

    bufData.writeByte(5.toByte)
    bufData.writeShort(1)
    bufData.writeByte(prio.toByte) // prio

    // 5 bytes for the header
    val bufHeader = Unpooled.buffer(55).order(ByteOrder.BIG_ENDIAN)
    bufHeader.writeByte(2.toByte) // Command set version 2
    bufHeader.writeInt(bufData.readableBytes)

    val buf = Unpooled.copiedBuffer(bufHeader, bufData)
    bufData.release
    bufHeader.release
    buf
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
