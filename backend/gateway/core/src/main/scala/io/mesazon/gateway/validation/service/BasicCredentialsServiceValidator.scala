package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.AuthenticationService.BasicCredentialsRequest
import io.mesazon.gateway.validation.domain.*
import cats.syntax.all.*
import zio.ZLayer

final class BasicCredentialsServiceValidator(
    emailDomainValidator: EmailDomainValidator
) extends ServiceValidator[BasicCredentialsRequest, BasicCredentials] {

  override def domainValidator: DomainValidator[BasicCredentialsRequest, BasicCredentials] = {
    basicCredentialsRequest =>
      emailDomainValidator
        .validate(basicCredentialsRequest.email)
        .map(validatedEmail =>
          (
            validatedEmail,
            validateRequiredField("password", basicCredentialsRequest.password, Password.either),
          ).mapN(BasicCredentials.apply)
        )
  }
}

object BasicCredentialsServiceValidator {

  val live = ZLayer.derive[BasicCredentialsServiceValidator]
}
