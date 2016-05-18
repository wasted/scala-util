package io.wasted.util

import java.net.{InetAddress, NetworkInterface}

import scala.collection.JavaConversions._

object HostInformation {

  /**
   * Get the hash of the jar-file being used.
   */
  def jarHash: Option[String] = {
    val path = this.getClass.getProtectionDomain.getCodeSource.getLocation.getPath
    val decodedPath = java.net.URLDecoder.decode(path, "UTF-8")
    new java.io.File(decodedPath).isFile match {
      case true => Some(Hashing.hexFileDigest(decodedPath)(HexingAlgo("SHA1")))
      case _ => None
    }
  }

  /**
   * Get all IP Addresses from this system.
   */
  def ipAddresses: List[InetAddress] = NetworkInterface.getNetworkInterfaces.flatMap { f =>
    f.getInetAddresses.map(_.getHostAddress.replaceAll("%.*$", "").toLowerCase) ++
      f.getSubInterfaces.flatMap(_.getInetAddresses.map(_.getHostAddress.replaceAll("%.*$", "").toLowerCase))
  }.toList.sortWith((e1, e2) => (e1 compareTo e2) < 0).map(InetAddress.getByName)

  /**
   * Get all MAC Addresses from this system.
   */
  def macAddresses: List[String] = NetworkInterface.getNetworkInterfaces.flatMap {
    case iface: NetworkInterface if iface.getHardwareAddress != null =>
      Some(iface.getHardwareAddress.map("%02X" format _).mkString(":"))
    case _ => None
  }.toList.sortWith((e1, e2) => (e1 compareTo e2) < 0)
}

