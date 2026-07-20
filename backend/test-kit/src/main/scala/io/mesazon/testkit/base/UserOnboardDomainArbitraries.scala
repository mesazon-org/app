package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait UserOnboardDomainArbitraries extends GatewayArbitraries {

  given arbOnboardPasswordPostRequest: Arbitrary[OnboardPasswordPostRequest] = Arbitrary(
    Gen.resultOf(OnboardPasswordPostRequest.apply)
  )

  given arbOnboardDetailsPostRequest: Arbitrary[OnboardDetailsPostRequest] = Arbitrary(
    Gen.resultOf(OnboardDetailsPostRequest.apply)
  )

  given arbOnboardVerifyPhoneNumberPostRequest: Arbitrary[OnboardVerifyPhoneNumberPostRequest] = Arbitrary(
    Gen.resultOf(OnboardVerifyPhoneNumberPostRequest.apply)
  )
}
