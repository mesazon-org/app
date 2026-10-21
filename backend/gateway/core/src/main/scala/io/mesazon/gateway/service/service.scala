package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import zio.*

type ServiceTask[A] = IO[ServiceError, A]

def verifyOnboardStage(
    userID: UserID,
    onboardStageUser: OnboardStage,
    onboardStagesAllowed: List[OnboardStage],
): IO[ServiceError.ForbiddenError.InvalidOnboardStage, Unit] =
  if (onboardStagesAllowed.contains(onboardStageUser)) ZIO.unit
  else
    ZIO.fail(
      ServiceError.ForbiddenError.InvalidOnboardStage(
        userID = userID,
        onboardStageUser = onboardStageUser,
        onboardStagesAllowed = onboardStagesAllowed,
      )
    )

val DevOtp = "123QWE"

def verifyOtpInDev(otp: Otp, isDev: Boolean): Boolean = isDev && otp.value == DevOtp
