package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class SignUpEmailPostRequestServiceValidator(
    emailDomainValidator: EmailDomainValidator
) extends ServiceValidator[smithy.SignUpEmailPostRequest, SignUpEmail] {

  val domainValidator: DomainValidator[smithy.SignUpEmailPostRequest, SignUpEmail] = { signUpEmailPostRequest =>
    emailDomainValidator.validate(signUpEmailPostRequest.email).map(_.map(SignUpEmail.apply))
  }
}

object SignUpEmailPostRequestServiceValidator {

  val live = ZLayer.derive[SignUpEmailPostRequestServiceValidator]
}
