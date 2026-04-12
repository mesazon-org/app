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

  given Arbitrary[smithy.OnboardPasswordRequest] = Arbitrary(
    Arbitrary.arbitrary[OnboardPassword].map(_.transformInto[smithy.OnboardPasswordRequest])
  )
}
