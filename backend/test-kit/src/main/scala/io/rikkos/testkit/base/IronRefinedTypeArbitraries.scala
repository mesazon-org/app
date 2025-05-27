package io.rikkos.testkit.base

import io.github.iltotore.iron.*
import io.rikkos.domain.{NonEmptyTrimmed, NonEmptyTrimmedLowerCase}
import org.scalacheck.*

trait IronRefinedTypeArbitraries {

  given arbNonEmptyTrimmedLowerCase: Arbitrary[String :| NonEmptyTrimmedLowerCase] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim.toLowerCase)
      .map(_.refineUnsafe[NonEmptyTrimmedLowerCase])
  }

  given arbNonEmptyTrimmed: Arbitrary[String :| NonEmptyTrimmed] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim)
      .map(_.refineUnsafe[NonEmptyTrimmed])
  }

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType],
      arb: Arbitrary[mirror.IronType],
  ): Arbitrary[WrappedType] = arb.asInstanceOf[Arbitrary[WrappedType]]
}

object IronRefinedTypeArbitraries extends IronRefinedTypeArbitraries
