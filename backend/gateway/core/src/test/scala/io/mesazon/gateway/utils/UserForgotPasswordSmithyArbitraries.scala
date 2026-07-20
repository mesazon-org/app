package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait UserForgotPasswordSmithyArbitraries extends UserForgotPasswordDomainArbitraries, IronRefinedTypeTransformer {

  given arbForgotPasswordPostRequestSmithy: Arbitrary[smithy.ForgotPasswordPostRequest] = Arbitrary(
    Arbitrary.arbitrary[ForgotPasswordPostRequest].map(_.transformInto[smithy.ForgotPasswordPostRequest])
  )

  given arbForgotPasswordVerifyOTPPostRequestSmithy: Arbitrary[smithy.ForgotPasswordVerifyOTPPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[ForgotPasswordVerifyOTPPostRequest]
      .map(_.transformInto[smithy.ForgotPasswordVerifyOTPPostRequest])
  )

  given arbForgotPasswordResetPostRequestSmithy: Arbitrary[smithy.ForgotPasswordResetPostRequest] = Arbitrary(
    Arbitrary.arbitrary[ForgotPasswordResetPostRequest].map(_.transformInto[smithy.ForgotPasswordResetPostRequest])
  )
}
