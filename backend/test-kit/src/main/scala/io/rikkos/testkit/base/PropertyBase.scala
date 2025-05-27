package io.rikkos.testkit.base

import org.scalactic.anyvals.PosInt
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

trait PropertyBase extends ScalaCheckPropertyChecks {

  def minSuccessful: PosInt = PosInt(10)

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(minSuccessful)
}
