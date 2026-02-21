package io.rikkos.testkit.base

import io.github.iltotore.iron.*
import io.rikkos.domain.*
import org.scalacheck.*

import java.time.Instant
import java.time.temporal.ChronoUnit

trait IronRefinedTypeArbitraries {

  given Arbitrary[Instant :| Pure] = Arbitrary(Gen.const(Instant.now().truncatedTo(ChronoUnit.MILLIS)))

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

  given arbWahaIDPredicate: Arbitrary[String :| WahaIDPredicate] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim.toLowerCase)
      .map(_ + "@c.us")
      .map(_.refineUnsafe[WahaIDPredicate])
  }

  given arbWahaGroupIDPredicate: Arbitrary[String :| WahaGroupIDPredicate] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim)
      .map(_ + "@g.us")
      .map(_.refineUnsafe[WahaGroupIDPredicate])
  }

  given arbWahaUserIDPredicate: Arbitrary[String :| WahaUserIDPredicate] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim)
      .map(_ + "@lid")
      .map(_.refineUnsafe[WahaUserIDPredicate])
  }

  given arbWhatsappIDPredicate: Arbitrary[String :| WhatsappIDPredicate] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim.toLowerCase)
      .map(_ + "@s.whatsapp.net")
      .map(_.refineUnsafe[WhatsappIDPredicate])
  }

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType],
      arb: Arbitrary[mirror.IronType],
  ): Arbitrary[WrappedType] = arb.asInstanceOf[Arbitrary[WrappedType]]
}

object IronRefinedTypeArbitraries extends IronRefinedTypeArbitraries
