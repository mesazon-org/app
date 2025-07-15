package io.rikkos.generator

import zio.*

trait IDGenerator {
  def generate: UIO[String]
}

object IDGenerator {

  final private class UUIDGenerator extends IDGenerator {
    override def generate: UIO[String] = Random.nextUUID.map(_.toString)
  }

  val uuidGeneratorLive: ULayer[IDGenerator] = ZLayer.succeed(new UUIDGenerator())
}
