import org.typelevel.sbt.tpolecat.TpolecatPlugin.autoImport.tpolecatScalacOptions
import org.typelevel.scalacoptions.ScalacOptions
import sbt.*
import sbt.Keys.*

object Settings {

  /** Publish the given docker images before the module's tests run.
    *
    * Every test entrypoint (`test`, `testQuick`, `testOnly`, `testFull`) is wrapped so the images are published
    * regardless of which one is invoked. sbt evaluates each task at most once per command, so the shared publish tasks
    * run once even though several keys depend on them (and even though `test` also delegates to `testQuick`) — no
    * double publishing.
    *
    * Each key is redefined as its own previous (default) value with the publishes added via `dependsOn`, so the images
    * are guaranteed to be published before the tests execute (the publishes run in parallel with each other, which is
    * fine as they are independent). This is the canonical "augment a task's dependencies" form that sbt resolves
    * against the key's previous value rather than as a cycle; for input tasks it goes through `InputTask.mapTask`, so
    * the completion parser (e.g. `testOnly <TAB>` test-name completion) is preserved.
    */
  def testAfterDockerPublish(dockerPublish: TaskKey[Unit]*): Seq[Def.Setting[?]] = {
    def wrapInput(key: InputKey[TestResult]): Def.Setting[?] =
      key := (Test / key).dependsOn(dockerPublish*).evaluated

    Seq(
      wrapInput(test),
      wrapInput(testQuick),
      wrapInput(testOnly),
      // `testFull` is a plain task; sbt's default opts it out of caching, so mirror that here
      testFull := Def.uncached((Test / testFull).dependsOn(dockerPublish*).value),
    )
  }

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
      ScalacOptions.other("-Wunused:all"),
      ScalacOptions.other("-Wconf:src=.*html&msg=unused import:s"),
    ),
    Test / tpolecatScalacOptions ++= Set(
      ignoreNotUsedAssertion,
      discardNonUnitAssertion,
    ),
  )
}
