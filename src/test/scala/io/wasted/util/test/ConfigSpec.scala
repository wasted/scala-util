package io.wasted.util.test

import io.wasted.util.Config

import org.specs2.mutable._
import java.net.InetSocketAddress

class ConfigSpec extends Specification {

  "Specification for Config functions.".title

  val ourInt = 5
  val ourBool = true
  val ourString = "chicken"
  val ourList = List("foo", "bar", "baz")
  val ourAddrs = List(new InetSocketAddress("127.0.0.1", 8080), new InetSocketAddress("::", 8081))

  "Preset Integer (" + ourInt + ")" should {
    val testVal = Config.getInt("test.int")
    "be the same as the config value (" + testVal + ")" in {
      Some(ourInt) must_== testVal
    }
  }

  "Preset Boolean (" + ourBool + ")" should {
    val testVal = Config.getBool("test.bool")
    "be the same as the config value (" + testVal + ")" in {
      Some(ourBool) must_== testVal
    }
  }

  "Preset String (" + ourString + ")" should {
    val testVal = Config.getString("test.string")
    "be the same as the config value (" + testVal + ")" in {
      Some(ourString) must_== testVal
    }
  }

  "Preset String-List (" + ourList + ")" should {
    val testVal = Config.getStringList("test.list")
    "be the same as the config value (" + testVal + ")" in {
      Some(ourList) must_== testVal
    }
  }

  "Preset InetSocketAddress-List (" + ourAddrs + ")" should {
    val testVal = Config.getInetAddrList("test.addrs")
    "be the same as the config value (" + testVal + ")" in {
      Some(ourAddrs) must_== testVal
    }
  }

  "Non-existing config-lookups" should {
    "for Integers be the None if no default-value was given" in {
      Config.getInt("foo") must_== None
    }
    "for Integers be Some(5) if a default-value of 5 was given" in {
      Config.getInt("foo", 5) must_== 5
    }
    "for Boolean be the None if no default-value was given" in {
      Config.getBool("foo") must_== None
    }
    "for Boolean be Some(true) if a default-value of true was given" in {
      Config.getBool("foo", true) must_== true
    }
    "for String be the None if no default-value was given" in {
      Config.getString("foo") must_== None
    }
    "for String be Some(bar) if a default-value of bar was given" in {
      Config.getString("foo", "bar") must_== "bar"
    }
    "for String-List be the None if no default-value was given" in {
      Config.getStringList("foo") must_== None
    }
    "for String-List be Some(List(\"bar\", \"baz\")) if a default-value was given" in {
      Config.getStringList("foo", List("bar", "baz")) must_== List("bar", "baz")
    }
    "for InetSocketAddress-List be the None if no default-value was given" in {
      Config.getInetAddrList("foo") must_== None
    }
    "for InetSocketAddress-List be Some(InetSocketAddress(\"1.2.3.4\", 80)) if a default-value was given" in {
      Config.getInetAddrList("foo", List("1.2.3.4:80")) must_== List(new java.net.InetSocketAddress("1.2.3.4", 80))
    }
  }

}

