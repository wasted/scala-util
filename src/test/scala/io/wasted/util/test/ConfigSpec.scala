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

}

