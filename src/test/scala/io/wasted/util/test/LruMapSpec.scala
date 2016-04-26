package io.wasted.util.test

import io.wasted.util.LruMap
import org.scalatest._

class LruMapSpec extends WordSpec {

  val lru = LruMap[Int, Int](10)

  "Pre-loaded LruMap with Ints (0 to 10)" should {
    for (i <- 0 to 10) lru.put(i, i)
    "not contain element 0 anymore" in { assert(lru.get(0).isEmpty) }
    "contain element 1" in { assert(lru.get(1).contains(1)) }
    "contain element 2" in { assert(lru.get(2).contains(2)) }
    "contain element 3" in { assert(lru.get(3).contains(3)) }
    "contain element 4" in { assert(lru.get(4).contains(4)) }
    "contain element 5" in { assert(lru.get(5).contains(5)) }
    "contain element 6" in { assert(lru.get(6).contains(6)) }
    "contain element 7" in { assert(lru.get(7).contains(7)) }
    "contain element 8" in { assert(lru.get(8).contains(8)) }
    "contain element 9" in { assert(lru.get(9).contains(9)) }
    "contain element 10" in { assert(lru.get(10).contains(10)) }
  }

  "Deleting Lru-Entries" should {
    "delete element 5 and verify" in {
      lru.remove(5)
      assert(lru.get(5).isEmpty)
    }
    "push element 0 and verify that 1 is still in" in {
      lru.put(0, 0)
      assert(lru.get(1).contains(1))
    }
  }

}

