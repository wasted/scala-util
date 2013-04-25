package io.wasted.util

import org.apache.commons.collections.map.LRUMap
import scala.collection.mutable.SynchronizedMap
import scala.collection.convert.Wrappers.JMapWrapper
import java.util.Map

object LruMap {
  def makeUnderlying[K, V](maxSize: Int) = new LRUMap(maxSize).asInstanceOf[Map[K, V]]
}

class LruMap[K, V](val maxSize: Int, underlying: Map[K, V]) extends JMapWrapper[K, V](underlying) {
  def this(maxSize: Int) = this(maxSize, LruMap.makeUnderlying(maxSize))
}

class SynchronizedLruMap[K, V](maxSize: Int, underlying: Map[K, V]) extends LruMap[K, V](maxSize, java.util.Collections.synchronizedMap(underlying)) with SynchronizedMap[K, V] {
  def this(maxSize: Int) = this(maxSize, LruMap.makeUnderlying(maxSize))
}

