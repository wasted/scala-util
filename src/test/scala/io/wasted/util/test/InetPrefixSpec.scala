package io.wasted.util.test

import io.wasted.util.InetPrefix
import java.net.InetAddress

import org.scalatest._

class InetPrefixSpec extends WordSpec {

  val ipv4network = InetPrefix(InetAddress.getByName("172.16.176.0"), 20)
  val ipv4first = InetAddress.getByName("172.16.176.0")
  val ipv4last = InetAddress.getByName("172.16.191.255")
  val ipv4invalid1 = InetAddress.getByName("172.16.175.5")
  val ipv4invalid2 = InetAddress.getByName("172.16.192.0")

  "IPv4 Network 172.16.176.0/20" should {
    "contain 172.16.176.0 as first valid address" in {
      assert(ipv4network.contains(ipv4first))
    }
    "contain 172.16.191.255 as last valid address" in {
      assert(ipv4network.contains(ipv4last))
    }
    "not contain 172.16.175.5" in {
      assert(!ipv4network.contains(ipv4invalid1))
    }
    "not contain 172.16.192.0" in {
      assert(!ipv4network.contains(ipv4invalid2))
    }
  }

  val ipv6first = InetAddress.getByName("2013:4ce8::")
  val ipv6network = InetPrefix(ipv6first, 32)
  val ipv6last = InetAddress.getByName("2013:4ce8:ffff:ffff:ffff:ffff:ffff:ffff")
  val ipv6invalid1 = InetAddress.getByName("2015:1234::")
  val ipv6invalid2 = InetAddress.getByName("aaaa:bbb::")

  "IPv6 Network 2013:4ce8::/32" should {
    "contain 2013:4ce8:: as first valid address" in {
      assert(ipv6network.contains(ipv6first))
    }
    "contain 2013:4ce8:ffff:ffff:ffff:ffff:ffff:ffff as last valid address" in {
      assert(ipv6network.contains(ipv6last))
    }
    "not contain 2015:1234::" in {
      assert(!ipv6network.contains(ipv6invalid1))
    }
    "not contain aaaa:bbb::" in {
      assert(!ipv6network.contains(ipv6invalid2))
    }
  }

  val ipv62addr = InetAddress.getByName("0::1")
  val ipv62network = InetPrefix(ipv62addr, 128)
  "IPv6 Network 0::1/128" should {
    "contain itself" in {
      assert(ipv62network.contains(ipv62addr))
    }
    "not contain 2015:1234::" in {
      assert(!ipv62network.contains(ipv6invalid1))
    }
    "not contain aaaa:bbb::" in {
      assert(!ipv62network.contains(ipv6invalid2))
    }
  }
}

