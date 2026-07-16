package io.mesazon.testkit.base

import com.dimafeng.testcontainers.*
import com.dimafeng.testcontainers.scalatest.TestContainerForAll
import org.scalatest.Suite
import org.slf4j.LoggerFactory
import org.testcontainers.containers.output.Slf4jLogConsumer
import zio.logging.loggerNameAnnotationKey

import java.io.File

trait DockerComposeBase extends TestContainerForAll { self: Suite =>

  // Drop the harmless NoClassDefFoundError that testcontainers' MountableFile.deleteOnExit shutdown
  // hook throws under sbt 2 (the forked test classloader is closed before the hook runs, so it can no
  // longer lazily load org.testcontainers.utility.PathUtils). Runs in the forked test JVM, so the
  // JVM-wide default handler suppresses only this one and prints everything else as before.
  //
  // The console error this suppresses:
  //   Exception in thread "Thread-15" java.lang.NoClassDefFoundError: org/testcontainers/utility/PathUtils
  //           at org.testcontainers.utility.MountableFile.lambda$deleteOnExit$0(MountableFile.java:318)
  //           at java.base/java.lang.Thread.run(Unknown Source)
  //   Caused by: java.lang.ClassNotFoundException: org.testcontainers.utility.PathUtils
  //           at java.base/java.net.URLClassLoader.findClass(Unknown Source)
  //           at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
  //           at java.base/java.lang.ClassLoader.loadClass(Unknown Source)
  //           ... 2 more
  Thread.setDefaultUncaughtExceptionHandler { (thread, error) =>
    val benignTestcontainersShutdown =
      error.isInstanceOf[NoClassDefFoundError] && Option(error.getMessage).exists(
        _.contains("org/testcontainers/utility/PathUtils")
      )
    if (!benignTestcontainersShutdown) {
      System.err.println(s"Exception in thread \"${thread.getName}\" $error")
      error.printStackTrace()
    }
  }

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
