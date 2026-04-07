package io.mesazon.clock

import zio.{Clock as _, *}

import java.time.*
import java.time.temporal.ChronoUnit

trait TimeProvider {
  def clock: UIO[Clock]
  def instantNow: UIO[Instant]
}

object TimeProvider {

  private final class TimeProviderImpl(javaClock: Clock) extends TimeProvider {

    override def clock: UIO[Clock] = ZIO.succeed(javaClock)

    override def instantNow: UIO[Instant] =
      ZIO.attempt(Instant.now(javaClock).truncatedTo(ChronoUnit.MILLIS)).orDie
  }

  val live: URLayer[Clock, TimeProvider] = ZLayer.derive[TimeProviderImpl]

  val liveSystemUTC: ULayer[TimeProvider] = ZLayer.succeed(Clock.systemUTC()) >>> live
}
