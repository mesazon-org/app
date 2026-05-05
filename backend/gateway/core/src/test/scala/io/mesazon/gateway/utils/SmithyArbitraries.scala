package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait SmithyArbitraries extends GatewayArbitraries, IronRefinedTypeTransformer {

  val genCyPhoneNationalNumber: Gen[String] = for {
    phoneNumberPrefix <- Gen.oneOf("99", "97", "96")
    phoneNumberSuffix <- Gen.choose(100000, 999999)
  } yield s"$phoneNumberPrefix$phoneNumberSuffix"

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

  given Arbitrary[smithy.SignUpEmailPostRequest] = Arbitrary {
    Arbitrary.arbitrary[SignUpEmail].map(_.transformInto[smithy.SignUpEmailPostRequest])
  }

  given Arbitrary[smithy.SignUpVerifyEmailPostRequest] = Arbitrary {
    for {
      verifyEmail <- Arbitrary.arbitrary[SignUpVerifyEmail]
      request = smithy.SignUpVerifyEmailPostRequest(otpID = verifyEmail.otpID.value, otp = verifyEmail.otp.value)
    } yield request
  }

  given Arbitrary[smithy.OnboardPasswordPostRequest] = Arbitrary(
    Arbitrary.arbitrary[OnboardPassword].map(_.transformInto[smithy.OnboardPasswordPostRequest])
  )

  given Arbitrary[smithy.OnboardDetailsPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[OnboardDetails]
      .map(
        _.into[smithy.OnboardDetailsPostRequest]
          .withFieldComputed(_.phoneNumber.phoneCountryCode, _.phoneNumber.phoneCountryCode.value)
          .withFieldComputed(_.phoneNumber.phoneNationalNumber, _.phoneNumber.phoneNationalNumber.value)
          .transform
      )
  )

  given Arbitrary[smithy.OnboardVerifyPhoneNumberPostRequest] = Arbitrary(
    Arbitrary.arbitrary[OnboardVerifyPhoneNumber].map(_.transformInto[smithy.OnboardVerifyPhoneNumberPostRequest])
  )
}
