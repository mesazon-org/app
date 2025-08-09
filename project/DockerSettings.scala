import com.typesafe.sbt.packager.Keys.*
import com.typesafe.sbt.packager.docker.*
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import sbt.*
import sbt.Keys.*

import java.nio.file.Paths

object DockerSettings {

  private val baseImage  = "gcr.io/distroless/java21-debian12:nonroot"
  private val dockerUser = "nonroot"

  private val dockerRegistry  = sys.env.get("CONTAINER_REGISTRY_HOST") orElse None
  private val dockerNamespace = sys.env.get("CONTAINER_NAMESPACE") orElse Some("eak")

// Commented out code is not used but reference
//  private def getAllSubDirs(dir: File): Seq[File] = {
//    val subDirs = dir.listFiles().filter(_.isDirectory).toSeq
//
//    subDirs ++ subDirs.flatMap(getAllSubDirs)
//  }
//
//  private def getRelativePath(parentFile: File, subFile: File): String = {
//    val parent   = Paths.get(parentFile.getPath)
//    val sub      = Paths.get(subFile.getPath)
//    val relative = parent.relativize(sub)
//    relative.toString
//  }

  val compileScope = Seq(
    Docker / packageName := "eak-" + name.value,
    Docker / version     := "latest",
    dockerExposedPorts   := Seq(8080, 8081),
    dockerCommands := {
      val appDir   = "/app"
      val workDir  = "/opt/docker"
      val jarsDir1 = "2/opt/docker/lib/"
      val jarsDir2 = "4/opt/docker/lib/"
      val main     = (Compile / packageBin / mainClass).value.getOrElse(sys.error("Expected exactly one main class"))
      val entry    = "java" +: javaOptions.value :+ "-cp" :+ "jars/*" :+ main
      Seq(
        Cmd("FROM", "gcr.io/distroless/java21-debian12:nonroot"),
        Cmd("USER", "nonroot"),
        Cmd("WORKDIR", workDir),
        Cmd("COPY", jarsDir1, "jars/"),
        Cmd("COPY", jarsDir2, "jars/"),
        ExecCmd("ENTRYPOINT", entry*),
      )
    },
  )
}
