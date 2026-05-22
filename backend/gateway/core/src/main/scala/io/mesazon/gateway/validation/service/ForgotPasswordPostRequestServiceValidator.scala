package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

import scala.util.chaining.scalaUtilChainingOps

final class ForgotPasswordPostRequestServiceValidator(
    emailDomainValidator: EmailDomainValidator
) extends ServiceValidator[smithy.ForgotPasswordPostRequest, ForgotPassword] {

  val domainValidator: DomainValidator[smithy.ForgotPasswordPostRequest, ForgotPassword] = {
    forgotPasswordPostRequest =>
      emailDomainValidator
        .validate(forgotPasswordPostRequest.email)
        .map(_.map(_.pipe(UserEmail.apply).pipe(ForgotPassword.apply)))
  }
}

object ForgotPasswordPostRequestServiceValidator {

  val live = ZLayer.derive[ForgotPasswordPostRequestServiceValidator]
}
