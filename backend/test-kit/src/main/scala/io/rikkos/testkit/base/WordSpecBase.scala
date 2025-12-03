package io.rikkos.testkit.base

import org.scalacheck.Arbitrary
import org.scalacheck.Gen
import org.scalatest.*
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import zio.NonEmptyChunk

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
    with BeforeAndAfterAll
    with BeforeAndAfterEach {

  implicit override val patienceConfig: PatienceConfig = PatienceConfig(20.seconds, 1.second)

  def arbitrarySample[T: Arbitrary as arb]: T =
    arb.arbitrary.sample.getOrElse(throw new NoSuchElementException("No sample available"))

  def arbitrarySample[T: Arbitrary as arb](number: Int): NonEmptyChunk[T] =
    NonEmptyChunk
      .fromIterableOption(
        Gen
          .listOfN(number, arb.arbitrary)
          .sample
          .getOrElse(throw new NoSuchElementException("No sample available"))
      )
      .get

}
