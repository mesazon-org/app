// Commented out code is not used but reference

//import sbt.*
//import sbt.Keys.*
//import sbtdocker.*
//import sbtdocker.DockerKeys.*
//
//object Docker {
//
//  private val dockerBaseImage = "gcr.io/distroless/java21-debian12:nonroot"
//  private val dockerUser      = "nonroot"
//
//  private val dockerRegistry  = sys.env.get("CONTAINER_REGISTRY_HOST") orElse None
//  private val dockerNamespace = sys.env.get("CONTAINER_NAMESPACE") orElse Some("eak")
//
//  private def buildDockerFile(scoped: Scoped, packageScope: Configuration): Def.Setting[Task[DockerfileBase]] =
//    scoped / dockerfile := {
//      val classpath = (packageScope / fullClasspathAsJars).value
//      val main = (packageScope / packageBin / mainClass).value.getOrElse(sys.error("Expected exactly one main class"))
//
//      val entry = "java" +: (packageScope / javaOptions).value :+ "-cp" :+ "jars/*" :+ main
//
//      new Dockerfile {
//        from(dockerBaseImage)
//        user(dockerUser)
//        workDir("/opt/docker")
//        copy(classpath.files, "jars/")
//        entryPoint(entry*)
//      }
//    }
//
//  private def setImageNames(scoped: Scoped): Def.Setting[Task[Seq[ImageName]]] =
//    scoped / imageNames := {
//      def imageName(registry: Option[String], version: String) =
//        ImageName(
//          registry = registry,
//          namespace = dockerNamespace,
//          repository = "eak-" + (scoped / name).value,
//          tag = Some(version),
//        )
//
//      if ((scoped / version).value == "local") Seq(imageName(dockerRegistry, "latest"))
//      else Seq(imageName(dockerRegistry, (scoped / version).value))
//    }
//
//  private def setBuildOptions(scoped: Scoped): Def.Setting[BuildOptions] = scoped / buildOptions := BuildOptions(
//    cache = true,
//    removeIntermediateContainers = BuildOptions.Remove.Always,
//    pullBaseImage = BuildOptions.Pull.IfMissing,
//  )
//
//  def settings(scoped: Scoped, packageScope: Configuration): Seq[Def.Setting[?]] =
//    Seq(setImageNames(scoped), buildDockerFile(scoped, packageScope), setBuildOptions(scoped))
//
//}
