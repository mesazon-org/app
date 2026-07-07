import sbt.*

object Dependencies {

  lazy val http4sV              = "0.23.34"
  lazy val smithy4sV            = "0.19.8"
  lazy val zioV                 = "2.1.26"
  lazy val zioConfigV           = "4.0.7"
  lazy val zioInteropCatsV      = "23.1.0.13"
  lazy val catsV                = "2.13.0"
  lazy val zioLoggingV          = "2.5.3"
  lazy val logbackV             = "1.5.37"
  lazy val julToSlf4jV          = "2.0.18"
  lazy val jclToSlf4jV          = "2.0.18"
  lazy val pureconfigV          = "0.17.10"
  lazy val scalaTestV           = "3.2.20"
  lazy val scalaTestPlusCheckV  = "3.2.11.0"
  lazy val scalacheckV          = "1.19.0"
  lazy val testcontainersScalaV = "0.44.1"
  lazy val testcontainersV      = "2.0.5"
  lazy val ironV                = "3.3.2"
  lazy val chimneyV             = "1.10.0"
  lazy val doobieV              = "1.0.0-RC13"
  lazy val postgresqlV          = "42.7.3"
  lazy val hikariCPV            = "7.1.0"
  lazy val doobieTranzactioV    = "6.0.0"
  lazy val libphonenumberV      = "9.0.34"
  lazy val circeV               = "0.14.16"
  lazy val sttpV                = "4.0.25"
  lazy val tapirV               = "1.13.25"
  lazy val jsoniterV            = "2.38.17"
  lazy val sttpAIV              = "0.5.1"
  lazy val jmailV               = "2.1.0"
  lazy val simplejavamailV      = "8.12.6"
  lazy val jjwtV                = "0.13.0"
  lazy val springSecurityV      = "7.1.0"
  lazy val springCoreV          = "7.0.8"
  lazy val bouncyCastleV        = "1.84"
  lazy val uuidCreatorV         = "6.1.1"
  lazy val scalamockV           = "7.5.5"
  lazy val scrimageV            = "4.6.5"
  lazy val tikaV                = "3.3.1"
  lazy val awssdkV              = "2.46.21"
  lazy val zioS3V               = "0.4.4"

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
  val ironChimney  = "io.github.iltotore" %% "iron-chimney"  % ironV
  val ironJsoniter = "io.github.iltotore" %% "iron-jsoniter" % ironV

  // Circe
  lazy val circeCore    = "io.circe" %% "circe-core"    % circeV
  lazy val circeGeneric = "io.circe" %% "circe-generic" % circeV
  lazy val circeParser  = "io.circe" %% "circe-parser"  % circeV

  // Logging
  lazy val logback    = "ch.qos.logback" % "logback-classic" % logbackV
  lazy val julToSlf4j = "org.slf4j"      % "jul-to-slf4j"    % julToSlf4jV
  lazy val jclToSlf4j = "org.slf4j"      % "jcl-over-slf4j"  % jclToSlf4jV

  // Test
  lazy val scalaTest                    = "org.scalatest"     %% "scalatest"                      % scalaTestV
  lazy val scalaTestPlusCheck           = "org.scalatestplus" %% "scalacheck-1-15"                % scalaTestPlusCheckV
  lazy val scalacheck                   = "org.scalacheck"    %% "scalacheck"                     % scalacheckV
  lazy val testcontainers               = "org.testcontainers" % "testcontainers"                 % testcontainersV
  lazy val testcontainersScalaScalatest = "com.dimafeng"      %% "testcontainers-scala-scalatest" % testcontainersScalaV
  lazy val scalamock                    = "org.scalamock"     %% "scalamock"                      % scalamockV
  lazy val scalamockZIO                 = "org.scalamock"     %% "scalamock-zio"                  % scalamockV

  // Chimney
  lazy val chimney = "io.scalaland" %% "chimney" % chimneyV

  // Doobie + Postgresql
  lazy val doobieCore       = "org.typelevel"        %% "doobie-core"       % doobieV
  lazy val doobieHikari     = "org.typelevel"        %% "doobie-hikari"     % doobieV
  lazy val doobiePostgres   = "org.typelevel"        %% "doobie-postgres"   % doobieV
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

  // STTP Iron

  // Tapir
  lazy val tapirCore            = "com.softwaremill.sttp.tapir" %% "tapir-core"              % tapirV
  lazy val tapirZIO             = "com.softwaremill.sttp.tapir" %% "tapir-zio"               % tapirV
  lazy val tapirHttp4s          = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"     % tapirV
  lazy val tapirSwaggerUIBundle = "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirV
  lazy val tapirIron            = "com.softwaremill.sttp.tapir" %% "tapir-iron"              % tapirV
  lazy val tapirHttp4sServerZIO = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server-zio" % tapirV
  lazy val tapirJsoniterScala   = "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala"    % tapirV

  // STTP AI
  lazy val sttpOpenAI    = "com.softwaremill.sttp.ai" %% "openai" % sttpAIV
  lazy val sttpOpenAIZIO = "com.softwaremill.sttp.ai" %% "zio"    % sttpAIV

  // JMail
  val jmail = "com.sanctionco.jmail" % "jmail" % jmailV

  // Simple Java Mail
  val simplejavamail = "org.simplejavamail" % "simple-java-mail" % simplejavamailV

  // JJWT
  lazy val jjwtApi     = "io.jsonwebtoken" % "jjwt-api"     % jjwtV
  lazy val jjwtImpl    = "io.jsonwebtoken" % "jjwt-impl"    % jjwtV % "runtime"
  lazy val jjwtJackson = "io.jsonwebtoken" % "jjwt-jackson" % jjwtV % "runtime"

  // Spring Security Crypto
  lazy val springSecurityCrypto = "org.springframework.security" % "spring-security-crypto" % springSecurityV
  // Required at runtime by spring-security-crypto 7.1.0's AbstractValidatingPasswordEncoder
  // (uses org.springframework.util.StringUtils), but not declared as a transitive dependency.
  lazy val springCore   = "org.springframework" % "spring-core"    % springCoreV
  lazy val bouncyCastle = "org.bouncycastle"    % "bcprov-jdk18on" % bouncyCastleV

  // UUID Creator
  lazy val uuidCreator = "com.github.f4b6a3" % "uuid-creator" % uuidCreatorV

  // Scrimage
  lazy val scrimageCore = "com.sksamuel.scrimage" % "scrimage-core" % scrimageV
  lazy val scrimageWebp = "com.sksamuel.scrimage" % "scrimage-webp" % scrimageV
  lazy val tikaCore     = "org.apache.tika"       % "tika-core"     % tikaV

  // AWS SDK
  val awssdkS3 = "software.amazon.awssdk" % "s3" % awssdkV
}
