package io.chrisdavenport.mules

import cats.effect.{Concurrent, Sync, Timer}

trait Cache[C[_, _, _], F[_], K, V] {

  def lookup(cache: C[F, K, V])(k: K): F[V]

  def size(cache: C[F, K, V]): F[Int]

  def keys(cache: C[F, K, V]): F[List[K]]

  def delete(cache: C[F, K, V])(k: K): F[Unit]

}


object Cache {

  def autoFetching[F[_] : Concurrent : Timer, K, V](defaultExpiration: Option[TimeSpec],
                                                    defaultReloadTime: Option[TimeSpec])
                                                   (fetch: K => F[V]): F[Cache[AutoFetchingCache, F, K, V]] = ???

  def unmanaged[F[_]: Sync, K, V](defaultExpiration: Option[TimeSpec]): F[Cache[UnmanagedCache, F, K, V]] = ???
}