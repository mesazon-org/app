package io.mesazon.gateway.config

import zio.Duration

case class UserOnboardConfig(
    isDev: Boolean,
    otpPhoneVerificationExpiresAtOffset: Duration,
    otpPhoneVerificationResendCooldown: Duration,
    sendWelcomeEmailMaxRetries: Int,
    sendWelcomeEmailRetryDelay: Duration,
    sendPhoneVerificationOtpMaxRetries: Int,
    sendPhoneVerificationOtpRetryDelay: Duration,
    otpVerifyAttemptsMaxRetries: Int,
)

object UserOnboardConfig {
  val live = deriveConfigLayer[UserOnboardConfig]("user-onboard")
}
