import Projects.ProjectOps
import smithy4s.codegen.Smithy4sCodegenPlugin

val enableScalaLint = sys.env.getOrElse("ENABLE_SCALA_LINT_ON_COMPILE", "true").toBoolean

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion              := "3.7.3"
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
    .settings(
      libraryDependencies += compilerPlugin("org.polyvariant" % "better-tostring" % "0.3.17" cross CrossVersion.full)
    )
}

lazy val root = Project("app", file("."))
  .aggregate(backendModule)
  .settings(Aliases.all)

// Backend modules
lazy val backendModule = Project("backend", file("backend"))
  .aggregate(
    backendDomainModule,
    backendClockModule,
    backendGeneratorModule,
    backendTestKitModule,
    backendPostgreSQLTestModule,
    backendGatewayRoot,
  )

lazy val backendDomainModule = createBackendModule("domain")(None)
  .withDependencies(Dependencies.iron)

lazy val backendClockModule = createBackendModule("clock")(None)
  .withDependencies(Dependencies.zio)

lazy val backendGeneratorModule = createBackendModule("generator")(None)
  .withDependencies(Dependencies.zio)

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
    Dependencies.testcontainers,
    Dependencies.testcontainersScalaScalatest,
    Dependencies.chimney,
  )

lazy val backendPostgreSQLTestModule = createBackendModule("postgresql-test")(None)
  .dependsOn(backendTestKitModule)
  .withDependencies(
    Dependencies.testcontainersScalaPostgresql,
    Dependencies.doobieCore,
    Dependencies.doobieHikari,
    Dependencies.doobiePostgres,
    Dependencies.doobieTranzactio,
    Dependencies.hikariCP,
  )

lazy val backendSchemas = createBackendModule("schemas")(None)

// Gateway
lazy val createBackendGatewayModule = createBackendModule("gateway") _

lazy val backendGatewayRoot = createBackendGatewayModule(None)
  .aggregate(backendGatewayCore, backendGatewayIt)

lazy val backendGatewayCore = createBackendGatewayModule(Some("core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(backendDomainModule)
  .dependsOn(backendClockModule)
  .dependsOn(backendGeneratorModule)
  .dependsOn(backendTestKitModule % Test)
  .dependsOn(backendPostgreSQLTestModule % Test)
  .settings(DockerSettings.compileScope)
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
    Dependencies.chimney,
    Dependencies.doobieCore,
    Dependencies.doobieHikari,
    Dependencies.doobiePostgres,
    Dependencies.doobieTranzactio,
    Dependencies.hikariCP,
    Dependencies.libphonenumber,
  )

lazy val backendGatewayIt = createBackendGatewayModule(Some("it"))
  .dependsOn(backendGatewayCore % "test->test")
  .dependsOn(backendTestKitModule % Test)
  .dependsOn(backendPostgreSQLTestModule % Test)
  .withDependencies(
    Dependencies.http4sEmberClient % Test,
    Dependencies.http4sCirce       % Test,
    Dependencies.circeCore         % Test,
    Dependencies.circeParser       % Test,
    Dependencies.circeGeneric      % Test,
  )
  .settings(
    test := Def
      .sequential(
        backendGatewayCore / Docker / publishLocal,
        Test / test,
      )
      .value
  )
