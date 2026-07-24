package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import zio.*

type ServiceTask[A] = IO[ServiceError, A]

def onboardStageFromDomainToSmithy(stage: OnboardStage): smithy.OnboardStage = stage match {
  case OnboardStage.EmailVerification => smithy.OnboardStage.EMAIL_VERIFICATION
  case OnboardStage.EmailVerified     => smithy.OnboardStage.EMAIL_VERIFIED
  case OnboardStage.PasswordProvided  => smithy.OnboardStage.PASSWORD_PROVIDED
  case OnboardStage.PhoneVerification => smithy.OnboardStage.PHONE_VERIFICATION
  case OnboardStage.PhoneVerified     => smithy.OnboardStage.PHONE_VERIFIED
}

def onboardStageFromSmithyToDomain(stage: smithy.OnboardStage): OnboardStage = stage match {
  case smithy.OnboardStage.EMAIL_VERIFICATION => OnboardStage.EmailVerification
  case smithy.OnboardStage.EMAIL_VERIFIED     => OnboardStage.EmailVerified
  case smithy.OnboardStage.PASSWORD_PROVIDED  => OnboardStage.PasswordProvided
  case smithy.OnboardStage.PHONE_VERIFICATION => OnboardStage.PhoneVerification
  case smithy.OnboardStage.PHONE_VERIFIED     => OnboardStage.PhoneVerified
}

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

def customerTypeFromDomainToSmithy(customerType: CustomerType): smithy.CustomerType =
  customerType match {
    case CustomerType.Individual => smithy.CustomerType.INDIVIDUAL
    case CustomerType.Business   => smithy.CustomerType.BUSINESS
  }

def organizationUserRoleFromSmithyToDomain(role: smithy.OrganizationUserRole): OrganizationUserRole = role match {
  case smithy.OrganizationUserRole.OWNER => OrganizationUserRole.Owner
  case smithy.OrganizationUserRole.ADMIN => OrganizationUserRole.Admin
  case smithy.OrganizationUserRole.USER  => OrganizationUserRole.User
}

val DevOtp = "123QWE"

def verifyOtpInDev(otp: Otp, isDev: Boolean): Boolean = isDev && otp.value == DevOtp
