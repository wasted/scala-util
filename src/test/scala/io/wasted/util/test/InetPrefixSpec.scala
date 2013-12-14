package io.wasted.util.test

import io.wasted.util.InetPrefix
import java.net.InetAddress

import org.specs2.mutable._

class InetPrefixSpec extends Specification {

  "Specification for Inet IPv4 and IPv6 calculations.".title

  val ipv4network = InetPrefix(InetAddress.getByName("172.16.176.0"), 20)
  val ipv4first = InetAddress.getByName("172.16.176.0")
  val ipv4last = InetAddress.getByName("172.16.191.255")
  val ipv4invalid1 = InetAddress.getByName("172.16.175.5")
  val ipv4invalid2 = InetAddress.getByName("172.16.192.0")

  "IPv4 Network 172.16.176.0/20" should {
    "contain 172.16.176.0 as first valid address" in {
      ipv4network.contains(ipv4first) must_== true
    }
    "contain 172.16.191.255 as last valid address" in {
      ipv4network.contains(ipv4last) must_== true
    }
    "not contain 172.16.175.5" in {
      ipv4network.contains(ipv4invalid1) must_== false
    }
    "not contain 172.16.192.0" in {
      ipv4network.contains(ipv4invalid2) must_== false
    }
  }

  val ipv6first = InetAddress.getByName("2013:4ce8::")
  val ipv6network = InetPrefix(ipv6first, 32)
  val ipv6last = InetAddress.getByName("2013:4ce8:ffff:ffff:ffff:ffff:ffff:ffff")
  val ipv6invalid1 = InetAddress.getByName("2015:1234::")
  val ipv6invalid2 = InetAddress.getByName("aaaa:bbb::")

  "IPv6 Network 2013:4ce8::/32" should {
    "contain 2013:4ce8:: as first valid address" in {
      ipv6network.contains(ipv6first) must_== true
    }
    "contain 2013:4ce8:ffff:ffff:ffff:ffff:ffff:ffff as last valid address" in {
      ipv6network.contains(ipv6last) must_== true
    }
    "not contain 2015:1234::" in {
      ipv6network.contains(ipv6invalid1) must_== false
    }
    "not contain aaaa:bbb::" in {
      ipv6network.contains(ipv6invalid2) must_== false
    }
  }

  val ipv62addr = InetAddress.getByName("0::1")
  val ipv62network = InetPrefix(ipv62addr, 128)
  "IPv6 Network 0::1/128" should {
    "contain itself" in {
      ipv62network.contains(ipv62addr) must_== true
    }
    "not contain 2015:1234::" in {
      ipv62network.contains(ipv6invalid1) must_== false
    }
    "not contain aaaa:bbb::" in {
      ipv62network.contains(ipv6invalid2) must_== false
    }
  }
}

