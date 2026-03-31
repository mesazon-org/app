package io.mesazon.domain.gateway

enum OnboardStage {
  case EmailConfirmation
  case EmailConfirmed
  case PasswordProvided
  case PhoneConfirmation
  case DetailsProvided
  case PhoneConfirmed
}

object OnboardStage {
  val signupEmailStages = List(
    OnboardStage.EmailConfirmation,
    OnboardStage.EmailConfirmed,
  )
}
