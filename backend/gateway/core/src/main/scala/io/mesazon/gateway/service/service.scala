package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
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

def catalogueItemStatusFromDomainToSmithy(status: CatalogueItemStatus): smithy.CatalogueItemStatus = status match {
  case CatalogueItemStatus.Active   => smithy.CatalogueItemStatus.Active
  case CatalogueItemStatus.Archived => smithy.CatalogueItemStatus.Archived
}

val DevOtp = "123QWE"

def verifyOtpInDev(otp: Otp, isDev: Boolean): Boolean = isDev && otp.value == DevOtp
