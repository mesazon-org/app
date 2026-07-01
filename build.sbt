import Projects.ProjectOps
import smithy4s.codegen.Smithy4sCodegenPlugin

val enableScalaLint = sys.env.getOrElse("ENABLE_SCALA_LINT_ON_COMPILE", "true").toBoolean

Global / onChangedBuildSource := ReloadOnSourceChanges

scalaVersion              := "3.8.4"
version                   := "latest"
organization              := "io.mesazon"
organizationName          := "Mesazon"
scalafixOnCompile         := enableScalaLint
scalafmtOnCompile         := enableScalaLint
semanticdbVersion         := scalafixSemanticdb.revision
semanticdbEnabled         := true
Test / fork               := true
run / fork                := true
Test / parallelExecution  := true
Test / testForkedParallel := true

lazy val backendDirName = "backend"

def createBackendModule(root: String)(subModule: Option[String]): Project = {
  val moduleName = subModule.map(sm => s"$root-$sm").getOrElse(root)
  val directory  = subModule.map(sm => s"$root/$sm").getOrElse(root)
  Project(moduleName, file(s"$backendDirName/$directory"))
    .settings(Settings.ScalaCompiler)
}

lazy val root = Project("app", file("."))
  .aggregate(
    backendModule,
    backendWahaModuleRoot,
  )
  .settings(Aliases.all)

// Backend modules
lazy val backendModule = Project("backend", file("backend"))
  .aggregate(
    backendDomainModule,
    backendClockModule,
    backendGeneratorModule,
    backendTestKitModule,
    backendPostgreSQLTestModule,
    backendS3TestModule,
    backendGatewayRoot,
    backendSchemas,
    backendWiremock,
  )

lazy val backendDomainModule = createBackendModule("domain")(None)
  .withDependencies(
    Dependencies.iron,
    Dependencies.cats,
  )

lazy val backendClockModule = createBackendModule("clock")(None)
  .withDependencies(Dependencies.zio)

lazy val backendGeneratorModule = createBackendModule("generator")(None)
  .dependsOn(backendClockModule)
  .withDependencies(
    Dependencies.zio,
    Dependencies.uuidCreator,
  )

lazy val backendTestKitModule = createBackendModule("test-kit")(None)
  .dependsOn(backendDomainModule)
  .withDependencies(
    Dependencies.zio,
    Dependencies.zioConfig,
    Dependencies.zioConfigTypesafe,
    Dependencies.zioConfigMagnolia,
    Dependencies.zioLogging,
    Dependencies.zioLoggingSL4J,
    Dependencies.zioInteropCats,
    Dependencies.scalaTest,
    Dependencies.scalacheck,
    Dependencies.scalaTestPlusCheck,
    Dependencies.scalamock,
    Dependencies.scalamockZIO,
    Dependencies.testcontainers,
    Dependencies.testcontainersScalaScalatest,
    Dependencies.chimney,
  )

lazy val backendPostgreSQLTestModule = createBackendModule("postgresql-test")(None)
  .dependsOn(backendTestKitModule)
  .withDependencies(
    Dependencies.doobieCore,
    Dependencies.doobieHikari,
    Dependencies.doobiePostgres,
    Dependencies.doobieTranzactio,
    Dependencies.hikariCP,
  )

lazy val backendS3TestModule = createBackendModule("s3-test")(None)
  .dependsOn(backendTestKitModule)
  .withDependencies(
    Dependencies.awssdkS3,
    Dependencies.zio,
    Dependencies.zioInteropCats,
  )

lazy val backendSchemas = createBackendModule("schemas")(None)

lazy val backendWiremock = createBackendModule("wiremock")(None)
  .enablePlugins(DockerPlugin)
  .settings(name := "wiremock")
  .settings(DockerWiremockSettings.settings)
  .withDependencies(
    Dependencies.zio,
    Dependencies.zioInteropCats,
    Dependencies.testcontainers,
    Dependencies.testcontainersScalaScalatest,
    Dependencies.sttpClient4ZIO,
    Dependencies.sttpClient4Jsoniter,
    Dependencies.jsoniterScalaCore,
    Dependencies.jsoniterScalaMacro,
  )

// Waha
lazy val createBackendWahaModule = createBackendModule("waha")

lazy val backendWahaModuleRoot = createBackendWahaModule(None)
  .aggregate(backendWahaModuleCore, backendWahaModuleIt)

lazy val backendWahaModuleCore = createBackendWahaModule(Some("core"))
  .dependsOn(backendDomainModule)
  .withDependencies(
    Dependencies.chimney,
    Dependencies.iron,
    Dependencies.ironChimney,
    Dependencies.ironJsoniter,
    Dependencies.zio,
    Dependencies.zioInteropCats,
    Dependencies.cats,
    Dependencies.jsoniterScalaCore,
    Dependencies.jsoniterScalaMacro,
    Dependencies.sttpClient4Core,
    Dependencies.sttpClient4Slf4j,
    Dependencies.sttpClient4ZIO,
    Dependencies.sttpClient4Jsoniter,
  )

lazy val backendWahaModuleIt = createBackendWahaModule(Some("it"))
  .dependsOn(backendTestKitModule)
  .dependsOn(backendWahaModuleCore % Test)
  .dependsOn(backendWiremock % Test)
  .settings(
    Test / test := (Test / test)
      .dependsOn(backendWiremock / Docker / publishLocal)
      .evaluated
  )

// Gateway
lazy val createBackendGatewayModule = createBackendModule("gateway")

lazy val backendGatewayRoot = createBackendGatewayModule(None)
  .aggregate(backendGatewayCore, backendGatewayIt)

lazy val backendGatewayCore = createBackendGatewayModule(Some("core"))
  .enablePlugins(Smithy4sCodegenPlugin)
  .enablePlugins(JavaAppPackaging, DockerPlugin, SbtTwirl)
  .dependsOn(backendDomainModule)
  .dependsOn(backendClockModule)
  .dependsOn(backendGeneratorModule)
  .dependsOn(backendWahaModuleCore)
  .dependsOn(backendTestKitModule % Test)
  .dependsOn(backendWiremock % Test)
  .dependsOn(backendPostgreSQLTestModule % Test)
  .dependsOn(backendS3TestModule % Test)
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
    Dependencies.smithy4sHttp4sSwagger,
    Dependencies.http4sDsl,
    Dependencies.http4sEmberServer,
    Dependencies.pureconfig,
    Dependencies.pureconfigCats,
    Dependencies.pureconfigCatsEffect,
    Dependencies.julToSlf4j,
    Dependencies.jclToSlf4j,
    Dependencies.logback,
    Dependencies.chimney,
    Dependencies.doobieCore,
    Dependencies.doobieHikari,
    Dependencies.doobiePostgres,
    Dependencies.doobieTranzactio,
    Dependencies.hikariCP,
    Dependencies.libphonenumber,
    Dependencies.sttpOpenAI,
    Dependencies.sttpOpenAIZIO,
    Dependencies.tapirIron,
    Dependencies.tapirHttp4sServerZIO,
    Dependencies.tapirJsoniterScala,
    Dependencies.tapirZIO,
    Dependencies.tapirHttp4s,
    Dependencies.tapirSwaggerUIBundle,
    Dependencies.sttpClient4Core,
    Dependencies.sttpClient4Slf4j,
    Dependencies.sttpClient4ZIO,
    Dependencies.sttpClient4Jsoniter,
    Dependencies.jmail,
    Dependencies.simplejavamail,
    Dependencies.jjwtApi,
    Dependencies.jjwtImpl,
    Dependencies.jjwtJackson,
    Dependencies.awssdkS3,
    Dependencies.scrimageCore,
    Dependencies.scrimageWebp,
    Dependencies.tikaCore,
    Dependencies.springSecurityCrypto,
    Dependencies.bouncyCastle,
  )
  .settings(
    Test / test := (Test / test)
      .dependsOn(backendWiremock / Docker / publishLocal)
      .evaluated
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
    Test / test := (Test / test)
      .dependsOn(
        backendWiremock / Docker / publishLocal,
        backendGatewayCore / Docker / publishLocal,
      )
      .evaluated
  )
