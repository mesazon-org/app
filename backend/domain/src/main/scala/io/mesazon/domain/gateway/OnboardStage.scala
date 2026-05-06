package io.mesazon.domain.gateway

// When new stages are added, make sure to update the following lists in the companion object:
enum OnboardStage {
  case EmailVerification
  case EmailVerified
  case PasswordProvided
  case PhoneVerification
  case PhoneVerified
}

object OnboardStage {
  val signInAllowedStages = List(
    OnboardStage.PasswordProvided,
    OnboardStage.PhoneVerification,
    OnboardStage.PhoneVerified,
  )

  val forgotPasswordAllowedStages = signInAllowedStages

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
