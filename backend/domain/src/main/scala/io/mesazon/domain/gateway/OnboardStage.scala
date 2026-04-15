package io.mesazon.domain.gateway

enum OnboardStage {
  case EmailVerification
  case EmailVerified
  case PasswordProvided
  case PhoneVerification
  case PhoneVerified
}

object OnboardStage {
  val signUpEmailStages = List(
    OnboardStage.EmailVerification,
    OnboardStage.EmailVerified,
  )
}
