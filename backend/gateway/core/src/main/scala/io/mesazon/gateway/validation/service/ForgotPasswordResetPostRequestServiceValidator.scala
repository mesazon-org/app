package io.mesazon.gateway.validation.service

import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.*
import zio.*

final class ForgotPasswordResetPostRequestServiceValidator
    extends ServiceValidator[smithy.ForgotPasswordResetPostRequest, ForgotPasswordReset] {

  override def domainValidator: DomainValidator[smithy.ForgotPasswordResetPostRequest, ForgotPasswordReset] = {
    forgotPasswordResetPostRequest =>
      ZIO.succeed(
        (
          validateRequiredField(
            "resetPasswordToken",
            forgotPasswordResetPostRequest.resetPasswordToken,
            ResetPasswordToken.either,
          ),
          validateRequiredField("password", forgotPasswordResetPostRequest.password, Password.either),
        ).mapN(ForgotPasswordReset.apply)
      )
  }
}

object ForgotPasswordResetPostRequestServiceValidator {

  val live = ZLayer.derive[ForgotPasswordResetPostRequestServiceValidator]
}
