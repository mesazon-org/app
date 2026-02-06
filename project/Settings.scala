import org.typelevel.sbt.tpolecat.TpolecatPlugin.autoImport.tpolecatScalacOptions
import org.typelevel.scalacoptions.ScalacOptions
import sbt.*
import sbt.Keys.*

object Settings {

  /** Ignore scalatest unused value Assertion for multi-line assertion in asserting
    *
    * @example
    *   {{{
    *   .asserting { entities =>
    *     entities should have size numOfUsers // <- WARN: unused value of type org.scalatest.Assertion
    *     entities.forall(_.ids.size == 30) shouldBe true
    *   }
    *   }}}
    */
  private val ignoreNotUsedAssertion =
    ScalacOptions.other("-Wconf:msg=unused value of type:s")

  /** Discard non-Unit value
    *
    * @example
    *   {{{
    *   eventually(
    *      gatewayApiClient.readiness.zioValue shouldBe Status.NoContent // <- WARN: discarded non-Unit value of type org.scalatest.Assertion
    *   )
    *   }}}
    */
  private val discardNonUnitAssertion =
    ScalacOptions.other("-Wconf:msg=discarded non-Unit value of type:s")

  lazy val ScalaCompiler = Def.settings(
    tpolecatScalacOptions ++= Set(
      ScalacOptions.other("-no-indent"),
      ScalacOptions.other("-experimental"),
      ScalacOptions.other("--preview"),
      ScalacOptions.other("-old-syntax"),
      ScalacOptions.other("-Wunused:unsafe-warn-patvars"),
      ScalacOptions.other("-Wunused:all"),
    ),
    Test / tpolecatScalacOptions ++= Set(
      ignoreNotUsedAssertion,
      discardNonUnitAssertion,
    ),
  )

  lazy val JavaOptions = javaOptions ++= Seq(
    "-Xmx2G",
    "-XX:+UseZGC",
    "-XX:+ZGenerational",
    "-XX:+IgnoreUnrecognizedVMOptions",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
  )
}
