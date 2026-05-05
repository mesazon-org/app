package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

final class OnboardPasswordPostRequestServiceValidator
    extends ServiceValidator[smithy.OnboardPasswordPostRequest, OnboardPassword] {

  val domainValidator: DomainValidator[smithy.OnboardPasswordPostRequest, OnboardPassword] = {
    onboardPasswordPostRequest =>
      ZIO.succeed(
        validateRequiredField("password", onboardPasswordPostRequest.password, Password.either).map(
          OnboardPassword.apply
        )
      )
  }
}

object OnboardPasswordPostRequestServiceValidator {

  val live = ZLayer.derive[OnboardPasswordPostRequestServiceValidator]
}
