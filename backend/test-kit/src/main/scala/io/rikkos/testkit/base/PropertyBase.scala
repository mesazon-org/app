package io.rikkos.testkit.base

import org.scalactic.anyvals.PosInt
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

trait PropertyBase extends ScalaCheckPropertyChecks {

  def minSuccessful: PosInt = PosInt(10)

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful)
}
