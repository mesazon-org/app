package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.{GatewayArbitraries, IronRefinedTypeTransformer}
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait SmithyArbitraries extends GatewayArbitraries, IronRefinedTypeTransformer {

  val genCyPhoneNationalNumber: Gen[String] = for {
    phoneNumberPrefix <- Gen.oneOf("99", "97", "96")
    phoneNumberSuffix <- Gen.choose(100000, 999999)
  } yield s"$phoneNumberPrefix$phoneNumberSuffix"

//  given Arbitrary[smithy.OnboardUserDetailsRequest] = Arbitrary {
//    for {
//      onboardUserDetails  <- Arbitrary.arbitrary[OnboardUserDetails]
//      phoneRegion         <- Gen.oneOf(Seq("CY"))
//      phoneNationalNumber <- genCyPhoneNationalNumber
//      onboardUserDetailsRequest = onboardUserDetails
//        .into[smithy.OnboardUserDetailsRequest]
//        .withFieldConst(_.phoneRegion, phoneRegion)
//        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
//        .transform
//    } yield onboardUserDetailsRequest
//  }

//  given Arbitrary[smithy.UpdateUserDetailsRequest] = Arbitrary {
//    for {
//      updateUserDetails   <- Arbitrary.arbitrary[UpdateUserDetails]
//      phoneRegion         <- Gen.option(Gen.oneOf(Seq("CY")))
//      phoneNationalNumber <- Gen.option(genCyPhoneNationalNumber)
//      updateUserDetailsRequest = updateUserDetails
//        .into[smithy.UpdateUserDetailsRequest]
//        .withFieldConst(_.phoneRegion, phoneRegion)
//        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
//        .transform
//    } yield updateUserDetailsRequest
//  }

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

  given Arbitrary[smithy.WahaMessageTextRequest] = Arbitrary {
    for {
      wahaMessage <- Arbitrary.arbitrary[WahaMessage]
      request = smithy.WahaMessageTextRequest(payload =
        smithy.Payload(
          id = wahaMessage.wahaMessageID.value,
          from = wahaMessage.wahaUserAccountID.value,
          body = "Body",
          data = smithy.InternalData(
            smithy.InternalInfo(
              sender = waha.WhatsAppPhoneNumber.fromUserAccountID(wahaMessage.wahaUserAccountID).value,
              senderAlt = wahaMessage.wahaUserID.value,
              pushName = wahaMessage.wahaFullName.value,
            )
          ),
        )
      )
    } yield request
  }

  given Arbitrary[smithy.SignUpEmailRequest] = Arbitrary {
    for {
      email <- Arbitrary.arbitrary[Email]
      request = smithy.SignUpEmailRequest(email = email.value)
    } yield request
  }

  given Arbitrary[smithy.VerifyEmailRequest] = Arbitrary {
    for {
      verifyEmail <- Arbitrary.arbitrary[VerifyEmail]
      request = smithy.VerifyEmailRequest(otpID = verifyEmail.otpID.value, otp = verifyEmail.otp.value)
    } yield request
  }
}
