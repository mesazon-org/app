package io.rikkos.gateway.it

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.github.iltotore.iron.circe.given
import io.rikkos.domain.*
import io.rikkos.testkit.base.IronRefinedTypeArbitraries
import org.scalacheck.*

package object domain extends IronRefinedTypeArbitraries {

  final case class OnboardUserDetailsRequest(
      firstName: FirstName,
      lastName: LastName,
      phoneRegion: PhoneRegion,
      phoneNationalNumber: PhoneNationalNumber,
      addressLine1: AddressLine1,
      addressLine2: Option[AddressLine2],
      city: City,
      postalCode: PostalCode,
      company: Company,
  )

  final case class UpdateUserDetailsRequest(
      firstName: Option[FirstName],
      lastName: Option[LastName],
      phoneRegion: Option[PhoneRegion],
      phoneNationalNumber: Option[PhoneNationalNumber],
      addressLine1: Option[AddressLine1],
      addressLine2: Option[AddressLine2],
      city: Option[City],
      postalCode: Option[PostalCode],
      company: Option[Company],
  )

  object OnboardUserDetailsRequest {
    given Arbitrary[OnboardUserDetailsRequest] = Arbitrary(Gen.resultOf(OnboardUserDetailsRequest.apply))

    given Encoder[OnboardUserDetailsRequest] = deriveEncoder[OnboardUserDetailsRequest]
  }

  object UpdateUserDetailsRequest {
    given Arbitrary[UpdateUserDetailsRequest] = Arbitrary(Gen.resultOf(UpdateUserDetailsRequest.apply))

    given Encoder[UpdateUserDetailsRequest] = deriveEncoder[UpdateUserDetailsRequest]
  }
}
