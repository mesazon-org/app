package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class OnboardPasswordServiceValidator extends ServiceValidator[smithy.OnboardPasswordRequest, OnboardPassword] {

  val domainValidator: DomainValidator[smithy.OnboardPasswordRequest, OnboardPassword] = { onboardPasswordRequest =>
    ZIO.succeed(
      validateRequiredField("password", onboardPasswordRequest.password, Password.either).map(OnboardPassword.apply)
    )
  }
}

object OnboardPasswordServiceValidator {

  val live = ZLayer.derive[OnboardPasswordServiceValidator]
}
