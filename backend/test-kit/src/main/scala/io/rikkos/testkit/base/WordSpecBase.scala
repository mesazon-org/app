package io.rikkos.testkit.base

import org.scalacheck.Arbitrary
import org.scalatest.*
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration.DurationInt

open class WordSpecBase
    extends AnyWordSpec
    with PropertyBase
    with should.Matchers
    with OptionValues
    with EitherValues
    with Eventually
    with ScalaFutures
    with LoneElement
    with BeforeAndAfterAll {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(20.seconds, 1.second)

  def arbitrarySample[T: Arbitrary as arb]: T =
    arb.arbitrary.sample.getOrElse(throw new NoSuchElementException("No sample available"))
}
