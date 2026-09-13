package io.mesazon.domain.gateway

enum ActionAttemptType {
  case SignIn
  case ForgotPassword
  case ForgotPasswordVerifyOTP
  case EmailVerificationVerifyOTP
  case PhoneVerificationVerifyOTP
  case EmailVerificationOtpLifetime
}
