package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import zio.*

type ServiceTask[A] = IO[ServiceError, A]

def onboardStageFromDomainToSmithy(stage: io.mesazon.domain.gateway.OnboardStage): smithy.OnboardStage = stage match {
  case OnboardStage.EmailVerification => smithy.OnboardStage.EMAIL_VERIFICATION
  case OnboardStage.EmailVerified     => smithy.OnboardStage.EMAIL_VERIFIED
  case OnboardStage.PasswordProvided  => smithy.OnboardStage.PASSWORD_PROVIDED
  case OnboardStage.PhoneVerification => smithy.OnboardStage.PHONE_VERIFICATION
  case OnboardStage.PhoneVerified     => smithy.OnboardStage.PHONE_VERIFIED
}

def onboardStageFromSmithyToDomain(stage: smithy.OnboardStage): io.mesazon.domain.gateway.OnboardStage = stage match {
  case smithy.OnboardStage.EMAIL_VERIFICATION => OnboardStage.EmailVerification
  case smithy.OnboardStage.EMAIL_VERIFIED     => OnboardStage.EmailVerified
  case smithy.OnboardStage.PASSWORD_PROVIDED  => OnboardStage.PasswordProvided
  case smithy.OnboardStage.PHONE_VERIFICATION => OnboardStage.PhoneVerification
  case smithy.OnboardStage.PHONE_VERIFIED     => OnboardStage.PhoneVerified
}

def verifyOnboardStage(
    onboardStageUser: OnboardStage,
    onboardStagesAllowed: List[OnboardStage],
): IO[ServiceError.UnauthorizedError.FailedOnboardStage, Unit] =
  if (onboardStagesAllowed.contains(onboardStageUser)) ZIO.unit
  else
    ZIO.fail(
      ServiceError.UnauthorizedError.FailedOnboardStage(
        onboardStageUser = onboardStageUser,
        onboardStagesAllowed = onboardStagesAllowed,
      )
    )
