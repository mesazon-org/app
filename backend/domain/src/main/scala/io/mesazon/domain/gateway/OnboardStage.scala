package io.mesazon.domain.gateway

enum OnboardStage {
  case EmailVerification
  case EmailVerified
  case PasswordProvided
  case DetailsProvided
  case PhoneVerification
  case PhoneVerified
}

object OnboardStage {
  val signupEmailStages = List(
    OnboardStage.EmailVerification,
    OnboardStage.EmailVerified,
  )

  val signupStages = List(
    OnboardStage.EmailVerification,
    OnboardStage.EmailVerified,
    OnboardStage.PasswordProvided,
    OnboardStage.DetailsProvided,
    OnboardStage.PhoneVerification,
  )
}
