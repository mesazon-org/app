package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class SignUpEmailServiceValidator(
    emailDomainValidator: EmailDomainValidator
) extends ServiceValidator[smithy.SignUpEmailRequest, SignUpEmail] {

  val domainValidator: DomainValidator[smithy.SignUpEmailRequest, SignUpEmail] = { signUpEmailRequest =>
    emailDomainValidator.validate(signUpEmailRequest.email).map(_.map(SignUpEmail.apply))
  }
}

object SignUpEmailServiceValidator {

  val live = ZLayer.derive[SignUpEmailServiceValidator]
}
