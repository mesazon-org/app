package io.rikkos.gateway.utils

import io.rikkos.domain.*
import io.rikkos.gateway.smithy
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait GatewayArbitraries extends DomainArbitraries, IronRefinedTypeTransformer {

  given Arbitrary[smithy.OnboardUserDetailsRequest] = Arbitrary {
    for {
      onboardUserDetails  <- Arbitrary.arbitrary[OnboardUserDetails]
      phoneRegion         <- Gen.oneOf(Seq("CY"))
      phoneNationalNumber <- Gen.const("99555555")
      onboardUserDetailsRequest = onboardUserDetails
        .into[smithy.OnboardUserDetailsRequest]
        .withFieldConst(_.phoneRegion, phoneRegion)
        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
        .transform
    } yield onboardUserDetailsRequest
  }

  given Arbitrary[smithy.UpdateUserDetailsRequest] = Arbitrary {
    for {
      updateUserDetails   <- Arbitrary.arbitrary[UpdateUserDetails]
      phoneRegion         <- Gen.option(Gen.oneOf(Seq("CY")))
      phoneNationalNumber <- Gen.option(Gen.const("99555555"))
      updateUserDetailsRequest = updateUserDetails
        .into[smithy.UpdateUserDetailsRequest]
        .withFieldConst(_.phoneRegion, phoneRegion)
        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
        .transform
    } yield updateUserDetailsRequest
  }

  given Arbitrary[smithy.UpsertUserContactRequest] = Arbitrary {
    for {
      upsertUserContact   <- Arbitrary.arbitrary[UpsertUserContact]
      phoneRegion         <- Gen.oneOf(Seq("CY"))
      phoneNationalNumber <- Gen.const("99555555")
      upsertUserContactRequest = upsertUserContact
        .into[smithy.UpsertUserContactRequest]
        .withFieldConst(_.phoneRegion, phoneRegion)
        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
        .transform
    } yield upsertUserContactRequest
  }
}
