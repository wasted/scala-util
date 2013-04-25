package io.wasted.util.test

import io.wasted.util.LruMap

import org.specs2.mutable._

class LruMapSpec extends Specification {

  "Specification for LruMap with size of 10.".title

  val lru = new LruMap[Int, Int](10)

  "Pre-loaded LruMap with Ints (0 to 10)" should {
    for (i <- 0 to 10) lru.put(i, i)
    "not contain element 0 anymore" in { lru.get(0) must beNone }
    "contain element 1" in { lru.get(1) must beSome(1) }
    "contain element 2" in { lru.get(2) must beSome(2) }
    "contain element 3" in { lru.get(3) must beSome(3) }
    "contain element 4" in { lru.get(4) must beSome(4) }
    "contain element 5" in { lru.get(5) must beSome(5) }
    "contain element 6" in { lru.get(6) must beSome(6) }
    "contain element 7" in { lru.get(7) must beSome(7) }
    "contain element 8" in { lru.get(8) must beSome(8) }
    "contain element 9" in { lru.get(9) must beSome(9) }
    "contain element 10" in { lru.get(10) must beSome(10) }
  }

}

