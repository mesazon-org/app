package io.mesazon.testkit.base

import com.dimafeng.testcontainers.*
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.Suite
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import zio.logging.loggerNameAnnotationKey

import java.io.File

trait DockerComposeBase extends TestContainerForAll { self: Suite =>

  def dockerComposeFile: String            = "compose.yaml"
  def exposedServices: Set[ExposedService] = Set.empty // Services to be exposed
  def logConsumers: Set[String]            = Set.empty // Specify the ServiceName

  override val containerDef: DockerComposeContainer.Def =
    DockerComposeContainer.Def(
      composeFiles = DockerComposeContainer.ComposeFile(Left(new File(s"$dockerComposeFile"))),
      exposedServices = exposedServices.toSeq,
      tailChildContainers = true,
      logConsumers = logConsumers
        .map(serviceName =>
          ServiceLogConsumer(
            serviceName,
            new Slf4jLogConsumer(LoggerFactory.getLogger(loggerNameAnnotationKey)).withPrefix(serviceName),
          )
        )
        .toSeq,
    )
}
