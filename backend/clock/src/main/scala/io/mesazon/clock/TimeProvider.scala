package io.mesazon.clock

import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock as JavaClock, *}

trait TimeProvider {
  def clock: UIO[JavaClock]
  def instantNow: UIO[Instant]
}

object TimeProvider {

  private final class TimeProviderImpl(javaClock: JavaClock) extends TimeProvider {

    override def clock: UIO[JavaClock] = ZIO.succeed(javaClock)

    override def instantNow: UIO[Instant] =
      ZIO.attempt(Instant.now(javaClock).truncatedTo(ChronoUnit.MILLIS)).orDie
  }

  val live: URLayer[JavaClock, TimeProvider] = ZLayer.derive[TimeProviderImpl]

  val liveSystemUTC: ULayer[TimeProvider] = ZLayer.succeed(JavaClock.systemUTC()) >>> live
}
