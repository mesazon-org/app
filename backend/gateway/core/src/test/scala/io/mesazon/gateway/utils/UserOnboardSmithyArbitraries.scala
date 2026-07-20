package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait UserOnboardSmithyArbitraries extends UserOnboardDomainArbitraries, IronRefinedTypeTransformer {

  given Transformer[PhoneNumber, smithy.PhoneNumberRequest] = phoneNumber =>
    smithy.PhoneNumberRequest(
      phoneNationalNumber = phoneNumber.phoneNationalNumber.value,
      phoneCountryCode = phoneNumber.phoneCountryCode.value,
    )

  given arbOnboardPasswordPostRequestSmithy: Arbitrary[smithy.OnboardPasswordPostRequest] = Arbitrary(
    Arbitrary.arbitrary[OnboardPasswordPostRequest].map(_.transformInto[smithy.OnboardPasswordPostRequest])
  )

  given arbOnboardDetailsPostRequestSmithy: Arbitrary[smithy.OnboardDetailsPostRequest] = Arbitrary(
    Arbitrary.arbitrary[OnboardDetailsPostRequest].map(_.transformInto[smithy.OnboardDetailsPostRequest])
  )

  given arbOnboardVerifyPhoneNumberPostRequestSmithy: Arbitrary[smithy.OnboardVerifyPhoneNumberPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[OnboardVerifyPhoneNumberPostRequest]
      .map(_.transformInto[smithy.OnboardVerifyPhoneNumberPostRequest])
  )
}
