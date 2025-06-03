import sbt.*

object Dependencies {

  lazy val http4sV              = "0.23.30"
  lazy val smithy4sV            = "0.18.34"
  lazy val zioV                 = "2.1.17"
  lazy val zioConfigV           = "4.0.4"
  lazy val zioInteropCatsV      = "23.1.0.5"
  lazy val catsV                = "2.13.0"
  lazy val zioLoggingV          = "2.5.0"
  lazy val logbackV             = "1.5.18"
  lazy val julToSlf4jV          = "2.0.17"
  lazy val pureconfigV          = "0.17.9"
  lazy val scalaTestV           = "3.2.19"
  lazy val scalaTestPlusCheckV  = "3.2.11.0"
  lazy val scalacheckV          = "1.18.1"
  lazy val testContainersScalaV = "0.43.0"
  lazy val testContainersJavaV  = "1.21.0"
  lazy val ironV                = "3.0.1"
  lazy val circeV               = "0.14.13"
  lazy val chimneyV             = "1.8.1"

  // Http4s
  lazy val http4sDsl         = "org.http4s" %% "http4s-dsl"          % http4sV
  lazy val http4sEmberServer = "org.http4s" %% "http4s-ember-server" % http4sV
  lazy val http4sEmberClient = "org.http4s" %% "http4s-ember-client" % http4sV
  lazy val http4sCirce       = "org.http4s" %% "http4s-circe"        % http4sV

  // Smithy
  lazy val smithy4sHttp4s = "com.disneystreaming.smithy4s" %% "smithy4s-http4s" % smithy4sV

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

  // IRON
  val iron      = "io.github.iltotore" %% "iron"       % ironV
  val ironCirce = "io.github.iltotore" %% "iron-circe" % ironV

  // Logging
  lazy val logback    = "ch.qos.logback" % "logback-classic" % logbackV
  lazy val julToSlf4j = "org.slf4j"      % "jul-to-slf4j"    % julToSlf4jV

  // Test
  lazy val scalaTest           = "org.scalatest"     %% "scalatest"                      % scalaTestV
  lazy val scalaTestPlusCheck  = "org.scalatestplus" %% "scalacheck-1-15"                % scalaTestPlusCheckV
  lazy val scalacheck          = "org.scalacheck"    %% "scalacheck"                     % scalacheckV
  lazy val testContainersJava  = "org.testcontainers" % "testcontainers"                 % testContainersJavaV
  lazy val testContainersScala = "com.dimafeng"      %% "testcontainers-scala-scalatest" % testContainersScalaV

  // Circe
  lazy val circeCore    = "io.circe" %% "circe-core"    % circeV
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeV
  lazy val circeParser  = "io.circe" %% "circe-parser"  % circeV

  // Chimney
  val chimney = "io.scalaland" %% "chimney" % chimneyV
}
