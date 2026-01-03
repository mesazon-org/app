import org.typelevel.sbt.tpolecat.TpolecatPlugin.autoImport.tpolecatScalacOptions
import org.typelevel.scalacoptions.ScalacOptions
import sbt.*

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

  /** Ignore couldn't resolve a member for the given link query *
    * @example
    *   {{{
    *   val doc = JsObject(
    *     "data" -> JsArray(entities.map(_.toJson).toVector)
    *   )
    *
    *   doc.hcursor.downField("data").as[List[Entity]] // <- WARN: Couldn't resolve a member for the given link query
    *   }}}
    */
  private val ignoreScala3Warning = ScalacOptions.other(
    "-Wconf:msg=Couldn't resolve a member for the given link query:s"
  )

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
      ignoreScala3Warning,
      ignoreNotUsedAssertion,
      discardNonUnitAssertion,
    ),
  )
}
