package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait UserSignUpDomainArbitraries extends GatewayArbitraries {

  given arbSignUpEmailPostRequest: Arbitrary[SignUpEmailPostRequest] = Arbitrary(
    Gen.resultOf(SignUpEmailPostRequest.apply)
  )

  given arbSignUpVerifyEmailPostRequest: Arbitrary[SignUpVerifyEmailPostRequest] = Arbitrary(
    Gen.resultOf(SignUpVerifyEmailPostRequest.apply)
  )
}
