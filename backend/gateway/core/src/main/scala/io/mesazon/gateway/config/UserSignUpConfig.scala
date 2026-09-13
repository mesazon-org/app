package io.mesazon.gateway.config

import zio.*

case class UserSignUpConfig(
    isDev: Boolean,
    otpEmailVerificationExpiresAtOffset: Duration,
    otpEmailVerificationResendCooldown: Duration,
    otpEmailVerificationResendAttemptsMaxRetries: Int,
    sendEmailVerificationEmailMaxRetries: Int,
    sendEmailVerificationEmailRetryDelay: Duration,
    otpVerifyAttemptsMaxRetries: Int,
)

object UserSignUpConfig {

  val live = deriveConfigLayer[UserSignUpConfig]("user-sign-up")
}
