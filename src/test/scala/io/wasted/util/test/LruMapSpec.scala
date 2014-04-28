package io.wasted.util.test

import io.wasted.util.LruMap

import org.scalatest._

class LruMapSpec extends WordSpec {

  val lru = LruMap[Int, Int](10)

  "Pre-loaded LruMap with Ints (0 to 10)" should {
    for (i <- 0 to 10) lru.put(i, i)
    "not contain element 0 anymore" in { assert(lru.get(0) == None) }
    "contain element 1" in { assert(lru.get(1) == Some(1)) }
    "contain element 2" in { assert(lru.get(2) == Some(2)) }
    "contain element 3" in { assert(lru.get(3) == Some(3)) }
    "contain element 4" in { assert(lru.get(4) == Some(4)) }
    "contain element 5" in { assert(lru.get(5) == Some(5)) }
    "contain element 6" in { assert(lru.get(6) == Some(6)) }
    "contain element 7" in { assert(lru.get(7) == Some(7)) }
    "contain element 8" in { assert(lru.get(8) == Some(8)) }
    "contain element 9" in { assert(lru.get(9) == Some(9)) }
    "contain element 10" in { assert(lru.get(10) == Some(10)) }
  }

}

