package io.mesazon.gateway.validation

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import zio.*

object OnboardPasswordValidator {

  private val onboardPasswordDomainValidatorLive: DomainValidator[smithy.OnboardPasswordRequest, OnboardPassword] = {
    request =>
      ZIO.succeed(
        validateRequiredField("password", request.password, Password.either).map(OnboardPassword.apply)
      )
  }

  val onboardPasswordValidatorLive = ZLayer.succeed(toServiceValidator(onboardPasswordDomainValidatorLive))
}
