package io.mesazon.generator

import com.github.f4b6a3.uuid.UuidCreator
import io.mesazon.clock.TimeProvider
import zio.*

import java.util.UUID

trait IDGenerator {
  def generate: UIO[UUID]
}

object IDGenerator {

  private final class IDGeneratorUUIDv7(timeProvider: TimeProvider) extends IDGenerator {
    override def generate: UIO[UUID] = timeProvider.instantNow.map(UuidCreator.getTimeOrderedEpoch)
  }

  val liveUUIDv7: URLayer[TimeProvider, IDGenerator] = ZLayer.fromFunction(new IDGeneratorUUIDv7(_))
}
