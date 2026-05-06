package io.mesazon.gateway.service

import io.mesazon.gateway.smithy

object UserForgotPasswordService {

  private final class UserForgotPasswordServiceImpl(
  ) extends smithy.UserForgotPasswordService[ServiceTask] {

    /** HTTP POST /forgot/password */
    override def forgotPasswordPost(
        request: smithy.ForgotPasswordPostRequest
    ): ServiceTask[smithy.ForgotPasswordPostResponse] = ???

    /** HTTP POST /forgot/password/verify */
    override def forgotPasswordVerifyPost(
        request: smithy.ForgotPasswordVerifyPostRequest
    ): ServiceTask[smithy.ForgotPasswordVerifyPostResponse] = ???

    /** HTTP POST /forgot/password/reset */
    override def forgotPasswordResetPost(request: smithy.ResetPasswordPostRequest): ServiceTask[Unit] = ???
  }

}
