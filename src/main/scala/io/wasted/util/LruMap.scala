package io.wasted.util

import com.google.common.cache._

private[util] case class KeyHolder[K](key: K)
private[util] case class ValueHolder[V](value: V)

/**
 * LruMap Companion to make creation easier
 */
object LruMap {
  def apply[K, V](maxSize: Int): LruMap[K, V] =
    new LruMap[K, V](maxSize, None, None)

  def apply[K, V](maxSize: Int, load: (K) => V): LruMap[K, V] =
    new LruMap[K, V](maxSize, Some(load), None)

  def apply[K, V](maxSize: Int, expire: (K, V) => Any): LruMap[K, V] =
    new LruMap[K, V](maxSize, None, Some(expire))

  def apply[K, V](maxSize: Int, load: (K) => V, expire: (K, V) => Any): LruMap[K, V] =
    new LruMap[K, V](maxSize, Some(load), Some(expire))
}

/**
 * LruMap Wrapper for Guava's Cache
 *
 * @param maxSize Maximum size of this cache
 * @param load Function to load objects
 * @param expire Function to be called on expired objects
 */
class LruMap[K, V](val maxSize: Int, load: Option[(K) => V], expire: Option[(K, V) => Any]) { lru =>
  private[this] val loader = lru.load.map { loadFunc =>
    new CacheLoader[KeyHolder[K], ValueHolder[V]] {
      def load(key: KeyHolder[K]): ValueHolder[V] = ValueHolder(loadFunc(key.key))
    }
  }

  private[this] val removal = lru.expire.map { expireFunc =>
    new RemovalListener[KeyHolder[K], ValueHolder[V]] {
      def onRemoval(removal: RemovalNotification[KeyHolder[K], ValueHolder[V]]): Unit =
        expireFunc(removal.getKey.key, removal.getValue.value)
    }
  }

  private[this] val cache: Cache[KeyHolder[K], ValueHolder[V]] = {
    val builder = CacheBuilder.newBuilder().maximumSize(maxSize)
    (loader, removal) match {
      case (Some(loaderO), Some(removalO)) => builder.removalListener(removalO).build(loaderO)
      case (Some(loaderO), None) => builder.build(loaderO)
      case (None, Some(removalO)) => builder.removalListener(removalO).build()
      case _ => builder.build()
    }
  }

  /**
   * Current size of this LRU Map
   */
  def size = cache.size.toInt

  /**
   * Puts a value
   * @param key Key to put the Value for
   * @param value Value to put for the Key
   */
  def put(key: K, value: V): Unit = cache.put(KeyHolder(key), ValueHolder(value))

  /**
   * Gets a value
   * @param key Key to get the Value for
   */
  def get(key: K): Option[V] = Option(cache.getIfPresent(KeyHolder(key))).map(_.value)
}

