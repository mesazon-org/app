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

  val signUpVerifyEmailStages = List(
    OnboardStage.EmailVerification
  )

  val onboardPasswordStages = List(
    OnboardStage.EmailVerified
  )

  val onboardDetailsStages = List(
    OnboardStage.PasswordProvided,
    OnboardStage.PhoneVerification,
  )

  val onboardVerifyPhoneNumberStages = List(
    OnboardStage.PhoneVerification
  )
}
