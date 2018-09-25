package io.chrisdavenport.mules

import cats._
import cats.data.OptionT
import cats.effect.{Sync, Clock}
// For Cats-effect 1.0
import cats.effect.concurrent.Ref
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.immutable.Map

class UnmanagedCache[F[_], K, V] private[UnmanagedCache](
                                                          private val ref: Ref[F, Map[K, UnmanagedCache.CacheItem[V]]],
                                                          val defaultExpiration: Option[UnmanagedCache.TimeSpec]
){
  // Lookups

  /**
    * Lookup an item with the given key, and delete it if it is expired.
    *
    * The function will only return a value if it is present in the cache and if the item is not expired.
    *
    * The function will eagerly delete the item from the cache if it is expired.
    **/
  def lookup(k: K)(implicit F: Sync[F], C: Clock[F]): F[Option[V]] =
    UnmanagedCache.lookup(this)(k)

  /**
    * Lookup an item with the given key, but don't delete it if it is expired.
    *
    * The function will only return a value if it is present in the cache and if the item is not expired.
    *
    * The function will not delete the item from the cache.
    **/
  def lookupNoUpdate(k: K)(implicit F: Sync[F], C: Clock[F]): F[Option[V]] =
    UnmanagedCache.lookupNoUpdate(this)(k)

  // Inserting

  /**
    * Insert an item in the cache, using the default expiration value of the cache.
    */
  def insert(k: K, v: V)(implicit F: Sync[F], C: Clock[F]): F[Unit] =
    UnmanagedCache.insert(this)(k, v)

  /**
    * Insert an item in the cache, with an explicit expiration value.
    *
    * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
    *
    * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
    **/
  def insertWithTimeout(timeout: Option[UnmanagedCache.TimeSpec])(k: K, v: V)(implicit F: Sync[F], C: Clock[F]) =
    UnmanagedCache.insertWithTimeout(this)(timeout)(k, v)

  // Deleting
  /**
    * Delete an item from the cache. Won't do anything if the item is not present.
    **/
  def delete(k: K)(implicit F: Sync[F]): F[Unit] = UnmanagedCache.delete(this)(k)

  /**
    * Delete all items that are expired.
    *
    * This is one big atomic operation.
    **/
  def purgeExpired(implicit F: Sync[F], C: Clock[F]) = UnmanagedCache.purgeExpired(this)

  // Informational

  /**
    * Return the size of the cache, including expired items.
    **/
  def size(implicit F: Sync[F]): F[Int] = UnmanagedCache.size(this)

  /**
    * Return all keys present in the cache, including expired items.
    **/
  def keys(implicit F: Sync[F]): F[List[K]] = UnmanagedCache.keys(this)
}

object UnmanagedCache {
  // Value of Time In Nanoseconds
  class TimeSpec private (
    val nanos: Long
  ) extends AnyVal
  object TimeSpec {

    def fromDuration(duration: FiniteDuration): Option[TimeSpec] =
      Alternative[Option].guard(duration > 0.nanos).as(unsafeFromDuration(duration))

    def unsafeFromDuration(duration: FiniteDuration): TimeSpec =
      new TimeSpec(duration.toNanos)

    def fromNanos(l: Long): Option[TimeSpec] =
      Alternative[Option].guard(l > 0).as(unsafeFromNanos(l))

    def unsafeFromNanos(l: Long): TimeSpec =
      new TimeSpec(l)

  }
  private case class CacheItem[A](
    item: A,
    itemExpiration: Option[TimeSpec]
  )

  /**
    * Create a new cache with a default expiration value for newly added cache items.
    *
    * Items that are added to the cache without an explicit expiration value (using insert) will be inserted with the default expiration value.
    *
    * If the specified default expiration value is None, items inserted by insert will never expire.
    **/
  def createCache[F[_]: Sync, K, V](defaultExpiration: Option[TimeSpec]): F[UnmanagedCache[F, K, V]] =
    Ref.of[F, Map[K, CacheItem[V]]](Map.empty[K, CacheItem[V]]).map(new UnmanagedCache[F, K, V](_, defaultExpiration))

  /**
    * Change the default expiration value of newly added cache items. Shares an underlying reference
    * with the other cache. Use copyCache if you want different caches.
    **/
  def setDefaultExpiration[F[_], K, V](cache: UnmanagedCache[F, K, V], defaultExpiration: Option[TimeSpec]): UnmanagedCache[F, K, V] =
    new UnmanagedCache[F, K, V](cache.ref, defaultExpiration)

  /**
    * Create a deep copy of the cache.
    **/
  def copyCache[F[_]: Sync, K, V](cache: UnmanagedCache[F, K, V]): F[UnmanagedCache[F, K, V]] = for {
    current <- cache.ref.get
    ref <- Ref.of[F, Map[K, CacheItem[V]]](current)
  } yield new UnmanagedCache[F, K, V](ref, cache.defaultExpiration)


  /**
    * Insert an item in the cache, using the default expiration value of the cache.
    */
  def insert[F[_]: Sync: Clock, K, V](cache: UnmanagedCache[F, K, V])(k: K, v: V): F[Unit] =
    insertWithTimeout(cache)(cache.defaultExpiration)(k, v)

  /**
    * Insert an item in the cache, with an explicit expiration value.
    *
    * If the expiration value is None, the item will never expire. The default expiration value of the cache is ignored.
    *
    * The expiration value is relative to the current clockMonotonic time, i.e. it will be automatically added to the result of clockMonotonic for the supplied unit.
    **/
  def insertWithTimeout[F[_]: Sync, K, V](cache: UnmanagedCache[F, K, V])(optionTimeout: Option[TimeSpec])(k: K, v: V)(implicit C: Clock[F]): F[Unit] =
    for {
      now <- C.monotonic(NANOSECONDS)
      timeout = optionTimeout.map(ts => TimeSpec.unsafeFromNanos(now + ts.nanos))
      _ <- cache.ref.update(m => m + (k -> CacheItem[V](v, timeout)))
    } yield ()


  /**
    * Return the size of the cache, including expired items.
    **/
  def size[F[_]: Sync, K, V](cache: UnmanagedCache[F, K, V]): F[Int] =
    cache.ref.get.map(_.size)

  /**
    * Return all keys present in the cache, including expired items.
    **/
  def keys[F[_]: Sync, K, V](cache: UnmanagedCache[F, K, V]): F[List[K]] =
    cache.ref.get.map(_.keys.toList)

  /**
    * Delete an item from the cache. Won't do anything if the item is not present.
    **/
  def delete[F[_]: Sync, K, V](cache: UnmanagedCache[F, K, V])(k: K): F[Unit] =
    cache.ref.update(m => m - (k)).void

  private def isExpired[A](checkAgainst: TimeSpec, cacheItem: CacheItem[A]): Boolean = {
    cacheItem.itemExpiration.fold(false){
      case e if e.nanos < checkAgainst.nanos => true
      case _ => false
    }
  }

  private def lookupItemSimple[F[_]: Sync, K, V](k: K, c: UnmanagedCache[F, K, V]): F[Option[CacheItem[V]]] =
    c.ref.get.map(_.get(k))


  /**
    * Internal Function Used for Lookup and management of values.
    * If isExpired and The boolean for delete is present then we delete,
    * otherwise return the value.
    **/
  private def lookupItemT[F[_]: Sync, K, V](del: Boolean, k: K, c: UnmanagedCache[F, K, V], t: TimeSpec): F[Option[CacheItem[V]]] = {
    val optionT = for {
      i <- OptionT(lookupItemSimple(k, c))
      e = isExpired(t, i)
      _ <- if (e && del) OptionT.liftF(delete(c)(k)) else OptionT.some[F](())
      result <- if (e) OptionT.none[F, CacheItem[V]] else OptionT.some[F](i)
    } yield result
    optionT.value
  }

  /**
    * Lookup an item with the given key, and delete it if it is expired.
    *
    * The function will only return a value if it is present in the cache and if the item is not expired.
    *
    * The function will eagerly delete the item from the cache if it is expired.
    **/
  def lookup[F[_]: Sync, K, V](c: UnmanagedCache[F, K, V])(k: K)(implicit C: Clock[F]): F[Option[V]] =
    C.monotonic(NANOSECONDS)
      .flatMap(now => lookupItemT(true, k, c, TimeSpec.unsafeFromNanos(now)))
      .map(_.map(_.item))

  /**
    * Lookup an item with the given key, but don't delete it if it is expired.
    *
    * The function will only return a value if it is present in the cache and if the item is not expired.
    *
    * The function will not delete the item from the cache.
    **/
  def lookupNoUpdate[F[_]: Sync, K, V](c: UnmanagedCache[F, K, V])(k: K)(implicit C: Clock[F]): F[Option[V]] =
    C.monotonic(NANOSECONDS)
      .flatMap(now => lookupItemT(false, k, c, TimeSpec.unsafeFromNanos(now)))
      .map(_.map(_.item))

  /**
    * Delete all items that are expired.
    *
    * This is one big atomic operation.
    **/
  def purgeExpired[F[_]: Sync, K, V](c: UnmanagedCache[F, K, V])(implicit C: Clock[F]): F[Unit] = {
    def purgeKeyIfExpired(m: Map[K, CacheItem[V]], k: K, checkAgainst: TimeSpec): Map[K, CacheItem[V]] = 
      m.get(k).fold(m)({item => if (isExpired(checkAgainst, item)) {m - (k) } else m})

    for {
      now <- C.monotonic(NANOSECONDS)
      _ <- c.ref.update(m => {m.keys.toList.foldLeft(m)((m, k) => purgeKeyIfExpired(m, k, TimeSpec.unsafeFromNanos(now)))}) // One Big Transactional Change
    } yield ()
  }

}