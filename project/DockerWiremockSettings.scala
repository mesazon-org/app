import DockerSettings.{baseImage, copyJarsDir1, copyJarsDir2, daemonUser, pasteJarsDir, workDir}
import com.typesafe.sbt.packager.Keys.*
import com.typesafe.sbt.packager.docker.{Cmd, ExecCmd}
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.Docker
import sbt.*
import sbt.Keys.*

object DockerWiremockSettings {

  private lazy val dockerRepositoryEnv = sys.env.get("DOCKER_REPOSITORY")
  private lazy val dockerTagEnv        = sys.env.get("DOCKER_IMAGE_TAG")

  private lazy val baseImage = "wiremock/wiremock:3.13.2"

  private lazy val entrypoint = Seq(
    "/docker-entrypoint.sh",
    "--global-response-templating",
    "--disable-gzip",
    "--async-response-enabled",
    "--no-request-journal",
    "--disable-request-logging",
    "--disable-banner",
    "--verbose",
  )

  val settings: Seq[Def.Setting[?]] = Def.settings(
    dockerAliases := dockerAliases.value.flatMap { alias =>
      Seq(
        Some(alias.withRegistryHost(Some("local"))),
        dockerRepositoryEnv.map(r => alias.withRegistryHost(Some(r))),
      ).flatten
    },
    dockerBaseImage      := baseImage,
    Docker / packageName := name.value,
    dockerUpdateLatest   := true,
    Docker / version     := dockerTagEnv.getOrElse(version.value),
    dockerExposedPorts   := Seq(8080),
    Docker / mappings ++= {
      val directory = baseDirectory.value / "mappings"
      directory.listFiles().map(f => f -> s"/mappings/${f.getName}").toSeq
    },
    dockerCommands := Seq(
      Cmd("FROM", baseImage),
      Cmd("WORKDIR", "/home/wiremock"),
      Cmd("COPY", "mappings/", "mappings/"),
      ExecCmd("ENTRYPOINT", entrypoint*),
    ),
  )
}
