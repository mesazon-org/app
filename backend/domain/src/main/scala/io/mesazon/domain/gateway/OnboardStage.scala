package io.mesazon.domain.gateway

enum OnboardStage {
  case EmailConfirmation
  case EmailConfirmed
  case DetailsProvided
  case PasswordProvided
  case PhoneConfirmation
  case PhoneConfirmed
}
