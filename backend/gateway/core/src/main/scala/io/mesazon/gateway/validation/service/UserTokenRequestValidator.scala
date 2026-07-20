package io.mesazon.gateway.validation.service

import cats.data.ValidatedNec
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import zio.*

final class UserTokenRequestValidator {

  def validatedTokenRefreshPostRequest(
      request: smithy.TokenRefreshPostRequest
  ): IO[ServiceError.BadRequestError.ValidationError, TokenRefreshPostRequest] =
    toValidatedRequestIO(validateTokenRefresh(request))

  private def validateTokenRefresh(
      request: smithy.TokenRefreshPostRequest
  ): UIO[ValidatedNec[InvalidFieldError, TokenRefreshPostRequest]] =
    ZIO.succeed(
      validateRequiredField("refreshToken", request.refreshToken, RefreshToken.either).map(
        TokenRefreshPostRequest.apply
      )
    )
}

object UserTokenRequestValidator {

  val live = ZLayer.derive[UserTokenRequestValidator]
}
