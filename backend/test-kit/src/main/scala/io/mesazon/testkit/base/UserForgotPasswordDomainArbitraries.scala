package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait UserForgotPasswordDomainArbitraries extends GatewayArbitraries {

  given arbForgotPasswordPostRequest: Arbitrary[ForgotPasswordPostRequest] = Arbitrary(
    Gen.resultOf(ForgotPasswordPostRequest.apply)
  )

  given arbForgotPasswordVerifyOTPPostRequest: Arbitrary[ForgotPasswordVerifyOTPPostRequest] = Arbitrary(
    Gen.resultOf(ForgotPasswordVerifyOTPPostRequest.apply)
  )

  given arbForgotPasswordResetPostRequest: Arbitrary[ForgotPasswordResetPostRequest] = Arbitrary(
    Gen.resultOf(ForgotPasswordResetPostRequest.apply)
  )
}
