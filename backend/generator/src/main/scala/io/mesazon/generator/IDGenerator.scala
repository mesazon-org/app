package io.mesazon.generator

import com.github.f4b6a3.uuid.UuidCreator
import io.mesazon.clock.TimeProvider
import zio.*

trait IDGenerator {
  def generate: UIO[String]
}

object IDGenerator {

  private object UUIDGenerator extends IDGenerator {
    override def generate: UIO[String] = Random.nextUUID.map(_.toString)
  }

  private final class UUIDv7IDGenerator(timeProvider: TimeProvider) extends IDGenerator {
    override def generate: UIO[String] = timeProvider.instantNow.map(UuidCreator.getTimeOrderedEpoch).map(_.toString)
  }

  val uuidGeneratorLive: ULayer[IDGenerator] = ZLayer.succeed(UUIDGenerator)

  val uuidV7IDGeneratorLive: URLayer[TimeProvider, IDGenerator] = ZLayer.fromFunction(new UUIDv7IDGenerator(_))
}
