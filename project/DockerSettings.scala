import com.typesafe.sbt.packager.Keys.*
import com.typesafe.sbt.packager.docker.*
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import sbt.*
import sbt.Keys.*

object DockerSettings {

  private lazy val dockerRepositoryEnv = sys.env.get("DOCKER_REPOSITORY") orElse Some("local")

  private lazy val daemonUser = "nonroot"
  private lazy val baseImage  = s"gcr.io/distroless/java21-debian12:$daemonUser"

  private lazy val appDir       = "/app"
  private lazy val workDir      = "/opt/docker"
  private lazy val pasteJarsDir = "jars/"
  private lazy val copyJarsDir1 = "2/opt/docker/lib/"
  private lazy val copyJarsDir2 = "4/opt/docker/lib/"

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

  val compileScope: Seq[Def.Setting[?]] = Def.settings(
    dockerRepository     := dockerRepositoryEnv,
    Docker / packageName := s"eak-${name.value}",
    dockerUpdateLatest   := true,
    Docker / version     := version.value,
    dockerExposedPorts   := Seq(8080),
    dockerCommands       := {
      val main  = (Compile / packageBin / mainClass).value.getOrElse(sys.error("Unspecified main class"))
      val entry = "java" +: javaOptions.value :+ "-cp" :+ "jars/*" :+ main
      Seq(
        Cmd("FROM", baseImage),
        Cmd("USER", daemonUser),
        Cmd("WORKDIR", workDir),
        Cmd("COPY", copyJarsDir1, pasteJarsDir),
        Cmd("COPY", copyJarsDir2, pasteJarsDir),
        ExecCmd("ENTRYPOINT", entry*),
      )
    },
  )
}
