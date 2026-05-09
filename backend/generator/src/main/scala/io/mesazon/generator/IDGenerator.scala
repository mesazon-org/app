package io.mesazon.generator

import com.github.f4b6a3.uuid.UuidCreator
import io.mesazon.clock.TimeProvider
import zio.*

trait IDGenerator {
  def generate: UIO[String]
}

object IDGenerator {

  private final class IDGeneratorUUIDv7(timeProvider: TimeProvider) extends IDGenerator {
    override def generate: UIO[String] = timeProvider.instantNow.map(UuidCreator.getTimeOrderedEpoch).map(_.toString)
  }

  val liveUUIDv7: URLayer[TimeProvider, IDGenerator] = ZLayer.fromFunction(new IDGeneratorUUIDv7(_))
}
