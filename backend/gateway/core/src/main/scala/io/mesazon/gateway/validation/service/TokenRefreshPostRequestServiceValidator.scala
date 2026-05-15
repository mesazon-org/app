package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class TokenRefreshPostRequestServiceValidator
    extends ServiceValidator[smithy.TokenRefreshPostRequest, TokenRefresh] {

  val domainValidator: DomainValidator[smithy.TokenRefreshPostRequest, TokenRefresh] = { onboardPasswordPostRequest =>
    ZIO.succeed(
      validateRequiredField("refreshToken", onboardPasswordPostRequest.refreshToken, RefreshToken.either).map(
        TokenRefresh.apply
      )
    )
  }
}

object TokenRefreshPostRequestServiceValidator {

  val live = ZLayer.derive[TokenRefreshPostRequestServiceValidator]
}
