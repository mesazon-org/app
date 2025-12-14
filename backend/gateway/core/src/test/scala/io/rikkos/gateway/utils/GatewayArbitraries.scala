package io.rikkos.gateway.utils

import io.rikkos.domain.*
import io.rikkos.gateway.smithy
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait GatewayArbitraries extends DomainArbitraries, IronRefinedTypeTransformer {

  val genCyPhoneNationalNumber: Gen[String] = for {
    phoneNumberPrefix <- Gen.oneOf("99", "97", "96")
    phoneNumberSuffix <- Gen.choose(100000, 999999)
  } yield s"$phoneNumberPrefix$phoneNumberSuffix"

  given Arbitrary[smithy.OnboardUserDetailsRequest] = Arbitrary {
    for {
      onboardUserDetails  <- Arbitrary.arbitrary[OnboardUserDetails]
      phoneRegion         <- Gen.oneOf(Seq("CY"))
      phoneNationalNumber <- genCyPhoneNationalNumber
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
      phoneNationalNumber <- Gen.option(genCyPhoneNationalNumber)
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
      phoneNationalNumber <- genCyPhoneNationalNumber
      upsertUserContactRequest = upsertUserContact
        .into[smithy.UpsertUserContactRequest]
        .withFieldConst(_.phoneRegion, phoneRegion)
        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
        .transform
    } yield upsertUserContactRequest
  }

  given Arbitrary[smithy.GetUserDetailsRequest] = Arbitrary(Gen.resultOf(smithy.GetUserDetailsRequest.apply))
}
