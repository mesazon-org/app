package io.mesazon.testkit.base

import org.scalacheck.{Arbitrary, Gen}
import org.scalamock.scalatest.MockFactory
import org.scalatest.*
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpec
import zio.NonEmptyChunk

import scala.concurrent.duration.DurationInt

open class WordSpecBase
    extends AnyWordSpec,
      MockFactory,
      PropertyBase,
      should.Matchers,
      OptionValues,
      EitherValues,
      Eventually,
      ScalaFutures,
      LoneElement,
      BeforeAndAfterAll,
      BeforeAndAfterEach {

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(30.seconds, 1.second)

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
