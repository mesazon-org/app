import sbt.*

object Dependencies {

  lazy val http4sV              = "0.23.33"
  lazy val smithy4sV            = "0.18.46"
  lazy val zioV                 = "2.1.24"
  lazy val zioConfigV           = "4.0.6"
  lazy val zioInteropCatsV      = "23.1.0.13"
  lazy val catsV                = "2.13.0"
  lazy val zioLoggingV          = "2.5.3"
  lazy val logbackV             = "1.5.24"
  lazy val julToSlf4jV          = "2.0.17"
  lazy val pureconfigV          = "0.17.9"
  lazy val scalaTestV           = "3.2.19"
  lazy val scalaTestPlusCheckV  = "3.2.11.0"
  lazy val scalacheckV          = "1.19.0"
  lazy val testcontainersScalaV = "0.44.1"
  lazy val testcontainersV      = "2.0.3"
  lazy val ironV                = "3.2.2"
  lazy val chimneyV             = "1.8.2"
  lazy val doobieV              = "1.0.0-RC10"
  lazy val postgresqlV          = "42.7.3"
  lazy val hikariCPV            = "7.0.2"
  lazy val doobieTranzactioV    = "5.6.0"
  lazy val libphonenumberV      = "9.0.21"
  lazy val circeV               = "0.14.15"
  lazy val sttpV                = "4.0.14"
  lazy val jsoniterV            = "2.38.8"

  // Http4s
  lazy val http4sDsl         = "org.http4s" %% "http4s-dsl"          % http4sV
  lazy val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sV
  lazy val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sV
  lazy val http4sCirce       = "org.http4s" %% "http4s-circe"        % http4sV

  // Smithy
  lazy val smithy4sHttp4s        = "com.disneystreaming.smithy4s" %% "smithy4s-http4s"         % smithy4sV
  lazy val smithy4sHttp4sSwagger = "com.disneystreaming.smithy4s" %% "smithy4s-http4s-swagger" % smithy4sV

  // Typelevel
  val cats = "org.typelevel" %% "cats-core" % catsV

  // ZIO
  lazy val zio               = "dev.zio" %% "zio"                 % zioV
  lazy val zioConfig         = "dev.zio" %% "zio-config"          % zioConfigV
  lazy val zioConfigMagnolia = "dev.zio" %% "zio-config-magnolia" % zioConfigV
  lazy val zioConfigTypesafe = "dev.zio" %% "zio-config-typesafe" % zioConfigV
  lazy val zioInteropCats    = "dev.zio" %% "zio-interop-cats"    % zioInteropCatsV
  lazy val zioLogging        = "dev.zio" %% "zio-logging"         % zioLoggingV
  lazy val zioLoggingSL4J    = "dev.zio" %% "zio-logging-slf4j"   % zioLoggingV

  // Pureconfig
  lazy val pureconfig           = "com.github.pureconfig" %% "pureconfig-core"        % pureconfigV
  lazy val pureconfigCats       = "com.github.pureconfig" %% "pureconfig-cats"        % pureconfigV
  lazy val pureconfigCatsEffect = "com.github.pureconfig" %% "pureconfig-cats-effect" % pureconfigV

  // Iron
  val iron         = "io.github.iltotore" %% "iron"          % ironV
  val ironJsoniter = "io.github.iltotore" %% "iron-jsoniter" % ironV

  // Circe
  lazy val circeCore    = "io.circe" %% "circe-core"    % circeV
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeV
  lazy val circeParser  = "io.circe" %% "circe-parser"  % circeV

  // Logging
  lazy val logback    = "ch.qos.logback" % "logback-classic" % logbackV
  lazy val julToSlf4j = "org.slf4j"      % "jul-to-slf4j"    % julToSlf4jV

  // Test
  lazy val scalaTest          = "org.scalatest"     %% "scalatest"       % scalaTestV
  lazy val scalaTestPlusCheck = "org.scalatestplus" %% "scalacheck-1-15" % scalaTestPlusCheckV
  lazy val scalacheck         = "org.scalacheck"    %% "scalacheck"      % scalacheckV
  lazy val testcontainers     = "org.testcontainers" % "testcontainers"  % testcontainersV
  lazy val testcontainersScalaPostgresql = "com.dimafeng" %% "testcontainers-scala-postgresql" % testcontainersScalaV
  lazy val testcontainersScalaScalatest  = "com.dimafeng" %% "testcontainers-scala-scalatest"  % testcontainersScalaV

  // Chimney
  lazy val chimney = "io.scalaland" %% "chimney" % chimneyV

  // Doobie + Postgresql
  lazy val doobieCore       = "org.tpolecat"         %% "doobie-core"       % doobieV
  lazy val doobieHikari     = "org.tpolecat"         %% "doobie-hikari"     % doobieV
  lazy val doobiePostgres   = "org.tpolecat"         %% "doobie-postgres"   % doobieV
  lazy val doobieTranzactio = "io.github.gaelrenoux" %% "tranzactio-doobie" % doobieTranzactioV
  lazy val hikariCP         = "com.zaxxer"            % "HikariCP"          % hikariCPV

  // Google
  val libphonenumber = "com.googlecode.libphonenumber" % "libphonenumber" % libphonenumberV

  // STTP Client4
  lazy val sttpClient4Core     = "com.softwaremill.sttp.client4" %% "core"          % sttpV
  lazy val sttpClient4Slf4j    = "com.softwaremill.sttp.client4" %% "slf4j-backend" % sttpV
  lazy val sttpClient4ZIO      = "com.softwaremill.sttp.client4" %% "zio"           % sttpV
  lazy val sttpClient4Jsoniter = "com.softwaremill.sttp.client4" %% "jsoniter"      % sttpV

  // jsoniter
  lazy val jsoniterScalaCore  = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core"   % jsoniterV
  lazy val jsoniterScalaMacro = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterV
}
