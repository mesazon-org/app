package io.rikkos.clock

import zio.{Clock as _, *}

import java.time.*
import java.time.temporal.ChronoUnit

trait TimeProvider {
  def instantNow: UIO[Instant]
}

object TimeProvider {

  private final class TimeProviderImpl(clock: Clock) extends TimeProvider {

    override def instantNow: UIO[Instant] =
      ZIO.attempt(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS)).orDie
  }

  val live: URLayer[Clock, TimeProvider] = ZLayer.derive[TimeProviderImpl]

  val liveSystemUTC: ULayer[TimeProvider] = ZLayer.succeed(Clock.systemUTC()) >>> live
}
