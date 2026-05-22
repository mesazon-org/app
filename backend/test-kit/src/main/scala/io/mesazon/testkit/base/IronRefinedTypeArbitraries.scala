package io.mesazon.testkit.base

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import io.mesazon.domain.*
import org.scalacheck.*

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

trait IronRefinedTypeArbitraries {

  given Arbitrary[Instant :| Pure] = Arbitrary(
    Gen.choose(0, 100).map(Instant.now().truncatedTo(ChronoUnit.MILLIS).plusMillis(_))
  )

  given arbUUIDPure: Arbitrary[UUID :| Pure] = Arbitrary(Gen.uuid.map(_.refineUnsafe[Pure]))

  given arbNonEmpty: Arbitrary[String :| NonEmpty] = Arbitrary {
    Gen
      .choose(1, 255)
      .flatMap(Gen.listOfN(_, Gen.alphaNumChar))
      .map(_.mkString)
      .map(_.refineUnsafe[NonEmpty])
  }

  given arbOtp: Arbitrary[String :| OtpPredicate] = Arbitrary {
    Gen
      .listOfN(6, Gen.alphaNumChar)
      .map(_.mkString.toUpperCase)
      .map(_.refineUnsafe[OtpPredicate])
  }

  given arbIntPositive: Arbitrary[Int :| Positive] = Arbitrary {
    Gen
      .choose(1, Int.MaxValue)
      .map(_.refineUnsafe[Positive])
  }

  given arbNonEmptyTrimmedLowerCase: Arbitrary[String :| NonEmptyTrimmedLowerCase] = Arbitrary {
    Gen
      .choose(1, 255)
      .flatMap(Gen.listOfN(_, Gen.alphaNumChar))
      .map(_.mkString.trim.toLowerCase)
      .map(_.refineUnsafe[NonEmptyTrimmedLowerCase])
  }

  given arbNonEmptyTrimmed: Arbitrary[String :| NonEmptyTrimmed] = Arbitrary {
    Gen
      .choose(1, 255)
      .flatMap(Gen.listOfN(_, Gen.alphaNumChar))
      .map(_.mkString.trim)
      .map(_.refineUnsafe[NonEmptyTrimmed])
  }

  given arbTokenPredicate: Arbitrary[String :| TokenPredicate] = Arbitrary {
    Gen
      .nonEmptyStringOf(Gen.alphaNumChar)
      .map(_.trim)
      .map(_.refineUnsafe[TokenPredicate])
  }

  given arbEmailPredicate: Arbitrary[String :| EmailPredicate] = Arbitrary {
    Gen
      .choose(3, 20)
      .flatMap(Gen.listOfN(_, Gen.alphaNumChar))
      .map(_.mkString.trim.toLowerCase)
      .map(_ + "@example.com")
      .map(_.refineUnsafe[EmailPredicate])
  }

  given arbPasswordPredicate: Arbitrary[String :| PasswordPredicate] = Arbitrary {
    Gen
      .listOfN(8, Gen.alphaNumChar)
      .map(_.mkString)
      .map(password => password + "Aa1@") // Ensure it meets the complexity requirements
      .map(_.refineUnsafe[PasswordPredicate])
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
      .nonEmptyStringOf(Gen.numChar)
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

  given [Internal](using arb: Arbitrary[Internal]): Arbitrary[Internal :| Pure] =
    arb.asInstanceOf[Arbitrary[Internal :| Pure]]

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType],
      arb: Arbitrary[mirror.IronType],
  ): Arbitrary[WrappedType] = arb.asInstanceOf[Arbitrary[WrappedType]]
}
