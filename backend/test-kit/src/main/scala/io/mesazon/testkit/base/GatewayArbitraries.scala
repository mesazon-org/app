package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import org.scalacheck.*

trait GatewayArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[PhoneNumber] = Arbitrary(
    for {
      phoneRegion <- Gen.oneOf(Seq("CY", "GB").map(PhoneRegion.assume))
      phoneCountryCode =
        if (phoneRegion.value == "CY") PhoneCountryCode.assume("+357") else PhoneCountryCode.assume("+44")
      phoneNationalNumber <-
        if (phoneCountryCode.value == "+357")
          Gen.oneOf("99123123", "94232312", "95123123").map(PhoneNationalNumber.assume)
        else Gen.oneOf("7754767565", "7423123243").map(PhoneNationalNumber.assume)
      phoneNumberE164 = PhoneNumberE164.assume(s"$phoneCountryCode$phoneNationalNumber")
    } yield PhoneNumber(phoneRegion, phoneCountryCode, phoneNationalNumber, phoneNumberE164)
  )

  given Arbitrary[PhoneCountryCode] = Arbitrary(Gen.oneOf(Seq("+357", "+44").map(PhoneCountryCode.assume)))

  given Arbitrary[PhoneRegion] = Arbitrary(Gen.oneOf(Seq("CY", "GB").map(PhoneRegion.assume)))

  given Arbitrary[PhoneNationalNumber] = Arbitrary(
    Gen.oneOf(Seq("7756745643", "99545545").map(PhoneNationalNumber.assume))
  )

  given Arbitrary[PhoneNumberE164] = Arbitrary(
    Gen.oneOf(Seq("+447756745643", "+35799545545").map(PhoneNumberE164.assume))
  )

  given Arbitrary[OtpType] = Arbitrary(Gen.oneOf(OtpType.values.toIndexedSeq))

  given Arbitrary[OnboardStage] = Arbitrary(Gen.oneOf(OnboardStage.values.toIndexedSeq))

  given Arbitrary[TokenType] = Arbitrary(Gen.oneOf(TokenType.values.toIndexedSeq))

  given Arbitrary[ActionAttemptType] = Arbitrary(Gen.oneOf(ActionAttemptType.values.toIndexedSeq))

  given Arbitrary[AuthedUser] = Arbitrary(Gen.resultOf(AuthedUser.apply))

  given Arbitrary[AssistantResponse] = Arbitrary(Gen.resultOf(AssistantResponse.apply))

  given Arbitrary[WahaMessage] = Arbitrary(
    for {
      wahaMessage     <- Gen.resultOf(WahaMessage.apply)
      phoneNumberE164 <- Arbitrary.arbitrary[PhoneNumberE164]
      wahaWhatsAppPhoneNumber = waha.WhatsAppPhoneNumber.assume(s"${phoneNumberE164.value.tail}@s.whatsapp.net")
      wahaUserAccountID       = waha.UserAccountID.assume(s"${phoneNumberE164.value.tail}@c.us")
    } yield wahaMessage.copy(
      wahaUserAccountID = wahaUserAccountID,
      wahaWhatsAppPhoneNumber = wahaWhatsAppPhoneNumber,
    )
  )

  given Arbitrary[SignUpEmail] = Arbitrary(Gen.resultOf(SignUpEmail.apply))

  given Arbitrary[SignUpVerifyEmail] = Arbitrary(Gen.resultOf(SignUpVerifyEmail.apply))

  given Arbitrary[OnboardPassword] = Arbitrary(Gen.resultOf(OnboardPassword.apply))

  given Arbitrary[OnboardDetails] = Arbitrary(Gen.resultOf(OnboardDetails.apply))

  given Arbitrary[OnboardVerifyPhoneNumber] = Arbitrary(Gen.resultOf(OnboardVerifyPhoneNumber.apply))

  given Arbitrary[BasicCredentials] = Arbitrary(Gen.resultOf(BasicCredentials.apply))
}
