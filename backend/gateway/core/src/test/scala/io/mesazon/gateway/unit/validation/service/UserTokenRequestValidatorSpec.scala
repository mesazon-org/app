package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.*

class UserTokenRequestValidatorSpec extends ZWordSpecBase, UserTokenSmithyArbitraries {

  private val validator: UserTokenRequestValidator = ZIO
    .service[UserTokenRequestValidator]
    .provide(UserTokenRequestValidator.live)
    .zioValue

  "UserTokenRequestValidator" should {
    "successfully validate a valid token refresh request" in {
      val tokenRefreshPostRequest = arbitrarySample[TokenRefreshPostRequest]

      validator
        .validatedTokenRefreshPostRequest(tokenRefreshPostRequest.transformInto[smithy.TokenRefreshPostRequest])
        .zioValue shouldBe tokenRefreshPostRequest
    }

    "fail to validate an invalid token refresh request" in {
      val tokenRefreshPostRequestSmithy = smithy.TokenRefreshPostRequest(refreshToken = "")

      validator.validatedTokenRefreshPostRequest(tokenRefreshPostRequestSmithy).zioError shouldBe
        ServiceError.BadRequestError.ValidationError(
          invalidFields = List(
            InvalidFieldError(
              "refreshToken",
              "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
              List(""),
            )
          )
        )
    }
  }
}
