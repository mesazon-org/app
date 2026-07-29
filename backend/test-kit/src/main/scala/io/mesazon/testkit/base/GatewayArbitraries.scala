package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import org.scalacheck.*

trait GatewayArbitraries extends IronRefinedTypeArbitraries {

  given arbPhoneNumber: Arbitrary[PhoneNumber] = Arbitrary(
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

  given arbPhoneCountryCode: Arbitrary[PhoneCountryCode] =
    Arbitrary(Gen.oneOf(Seq("+357", "+44").map(PhoneCountryCode.assume)))

  given arbPhoneRegion: Arbitrary[PhoneRegion] = Arbitrary(Gen.oneOf(Seq("CY", "GB").map(PhoneRegion.assume)))

  given arbPhoneNationalNumber: Arbitrary[PhoneNationalNumber] = Arbitrary(
    Gen.oneOf(Seq("7756745643", "99545545").map(PhoneNationalNumber.assume))
  )

  given arbPhoneNumberE164: Arbitrary[PhoneNumberE164] = Arbitrary(
    Gen.oneOf(Seq("+447756745643", "+35799545545").map(PhoneNumberE164.assume))
  )

  given arbOtpType: Arbitrary[OtpType] = Arbitrary(Gen.oneOf(OtpType.values.toIndexedSeq))

  given arbOnboardStage: Arbitrary[OnboardStage] = Arbitrary(Gen.oneOf(OnboardStage.values.toIndexedSeq))

  given arbTokenType: Arbitrary[TokenType] = Arbitrary(Gen.oneOf(TokenType.values.toIndexedSeq))

  given arbActionAttemptType: Arbitrary[ActionAttemptType] =
    Arbitrary(Gen.oneOf(ActionAttemptType.values.toIndexedSeq))

  given arbPrice: Arbitrary[Price] = Arbitrary(Gen.resultOf(Price.apply))

  given arbPhoto: Arbitrary[Photo] = Arbitrary(Gen.resultOf(Photo.apply))

  given arbAuthedUser: Arbitrary[AuthedUser] = Arbitrary(Gen.resultOf(AuthedUser.apply))

  given arbAssistantResponse: Arbitrary[AssistantResponse] = Arbitrary(Gen.resultOf(AssistantResponse.apply))

  given arbWahaMessage: Arbitrary[WahaMessage] = Arbitrary(
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

  // A non-empty list must mark exactly one entry as default, so generate non-default entries and promote one at random.
  protected def genEntriesWithSingleDefault[A](genEntry: Gen[A])(setDefault: A => A): Gen[List[A]] =
    Gen.listOf(genEntry).flatMap {
      case Nil     => Gen.const(Nil)
      case entries =>
        Gen
          .choose(0, entries.length - 1)
          .map(defaultIndex => entries.updated(defaultIndex, setDefault(entries(defaultIndex))))
    }

}
