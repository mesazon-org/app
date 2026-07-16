import com.typesafe.sbt.packager.Keys.*
import com.typesafe.sbt.packager.docker.*
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import sbt.*
import sbt.Keys.*

object DockerSettings {

  private lazy val dockerRepositoryEnv = sys.env.get("DOCKER_REPOSITORY")
  private lazy val dockerTagEnv        = sys.env.get("DOCKER_IMAGE_TAG")

  private lazy val daemonUser = "nonroot"
  private lazy val baseImage  = s"gcr.io/distroless/java21-debian12:$daemonUser"

  private lazy val workDir      = "/opt/docker"
  private lazy val pasteJarsDir = "jars/"
  // Native-packager docker stage layers: 2/ holds dependency jars, 4/ holds the app jars
  private lazy val copyJarsDir1 = "2/opt/docker/lib/"
  private lazy val copyJarsDir2 = "4/opt/docker/lib/"

  val compileScope: Seq[Def.Setting[?]] = Def.settings(
    dockerAliases := dockerAliases.value.flatMap { alias =>
      Seq(
        Some(alias.withRegistryHost(Some("local"))),
        dockerRepositoryEnv.map(r => alias.withRegistryHost(Some(r))),
      ).flatten
    },
    Docker / packageName := name.value,
    dockerUpdateLatest   := true,
    Docker / version     := dockerTagEnv.getOrElse(version.value),
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
