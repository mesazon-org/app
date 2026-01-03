package io.rikkos.generator

import zio.*

trait IDGenerator {
  def generate: UIO[String]
}

object IDGenerator {

  private object UUIDGenerator extends IDGenerator {
    override def generate: UIO[String] = Random.nextUUID.map(_.toString)
  }

  val uuidGeneratorLive: ULayer[IDGenerator] = ZLayer.succeed(UUIDGenerator)
}
