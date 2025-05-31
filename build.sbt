import Projects.ProjectOps
import smithy4s.codegen.Smithy4sCodegenPlugin

val enableScalaLint = sys.env.getOrElse("ENABLE_SCALA_LINT_ON_COMPILE", "true").toBoolean

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion              := "3.6.4"
ThisBuild / version                   := "local"
ThisBuild / organization              := "io.rikkos"
ThisBuild / organizationName          := "Rikkos"
ThisBuild / scalafixOnCompile         := enableScalaLint
ThisBuild / scalafmtOnCompile         := enableScalaLint
ThisBuild / semanticdbVersion         := scalafixSemanticdb.revision
ThisBuild / semanticdbEnabled         := true
ThisBuild / Test / fork               := true
ThisBuild / run / fork                := true
ThisBuild / Test / parallelExecution  := true
ThisBuild / Test / testForkedParallel := true

lazy val backendDirName = "backend"

def createBackendModule(root: String)(subModule: Option[String]): Project = {
  val moduleName = subModule.map(sm => s"$root-$sm").getOrElse(root)
  val directory  = subModule.map(sm => s"$root/$sm").getOrElse(root)
  Project(moduleName, file(s"$backendDirName/$directory"))
    .settings(Settings.ScalaCompiler)
}

lazy val root = Project("app", file("."))
  .aggregate(backendModule)
  .settings(Aliases.all)

// Backend modules
lazy val backendModule = Project("backend", file("backend"))
  .aggregate(backendDomainModule, backendTestKitModule, backendGatewayRoot)

lazy val backendDomainModule = createBackendModule("domain")(None)
  .withDependencies(Dependencies.iron)

lazy val backendTestKitModule = createBackendModule("test-kit")(None)
  .dependsOn(backendDomainModule)
  .withDependencies(
    Dependencies.zio,
    Dependencies.zioConfig,
    Dependencies.zioConfigTypesafe,
    Dependencies.zioLogging,
    Dependencies.zioLoggingSL4J,
    Dependencies.zioInteropCats,
    Dependencies.scalaTest,
    Dependencies.scalacheck,
    Dependencies.scalaTestPlusCheck,
    Dependencies.testContainersScala,
    Dependencies.testContainersJava,
  )

lazy val backendSchemas = createBackendModule("schemas")(None)

// Gateway
lazy val createBackendGatewayModule = createBackendModule("gateway") _

lazy val backendGatewayRoot = createBackendGatewayModule(None)
  .aggregate(backendGatewayCore, backendGatewayIt)

lazy val backendGatewayCore = createBackendGatewayModule(Some("core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .enablePlugins(DockerPlugin)
  .dependsOn(backendTestKitModule % Test)
  .dependsOn(backendDomainModule)
  .settings(Docker.settings(docker, Compile))
  .withDependencies(
    Dependencies.zio,
    Dependencies.zioConfig,
    Dependencies.zioConfigMagnolia,
    Dependencies.zioConfigTypesafe,
    Dependencies.zioLogging,
    Dependencies.zioLoggingSL4J,
    Dependencies.zioInteropCats,
    Dependencies.smithy4sHttp4s,
    Dependencies.http4sDsl,
    Dependencies.http4sEmberServer,
    Dependencies.pureconfig,
    Dependencies.pureconfigCats,
    Dependencies.pureconfigCatsEffect,
    Dependencies.julToSlf4j,
    Dependencies.logback,
  )

lazy val backendGatewayIt = createBackendGatewayModule(Some("it"))
  .dependsOn(backendGatewayCore % Test)
  .dependsOn(backendTestKitModule % Test)
  .withDependencies(
    Dependencies.http4sEmberClient,
    Dependencies.http4sCirce,
    Dependencies.ironCirce,
    Dependencies.circeCore,
    Dependencies.circeGeneric,
    Dependencies.circeParser,
  )
  .settings(
    test := {
      val testResult = (backendGatewayCore / docker).value
      (Test / test).value
    }
  )
