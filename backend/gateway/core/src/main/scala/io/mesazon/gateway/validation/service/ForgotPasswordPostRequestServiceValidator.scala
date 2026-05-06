package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class ForgotPasswordPostRequestServiceValidator(
    emailDomainValidator: EmailDomainValidator
) extends ServiceValidator[smithy.ForgotPasswordPostRequest, ForgotPassword] {

  val domainValidator: DomainValidator[smithy.ForgotPasswordPostRequest, ForgotPassword] = {
    forgotPasswordPostRequest =>
      emailDomainValidator.validate(forgotPasswordPostRequest.email).map(_.map(ForgotPassword.apply))
  }
}

object ForgotPasswordPostRequestServiceValidator {

  val live = ZLayer.derive[ForgotPasswordPostRequestServiceValidator]
}
