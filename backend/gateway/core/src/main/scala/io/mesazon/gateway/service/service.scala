package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import zio.*

type ServiceTask[A] = IO[ServiceError, A]

def onboardStageFromSmithyToDomain(stage: smithy.OnboardStage): OnboardStage = stage match {
  case smithy.OnboardStage.EmailVerification => OnboardStage.EmailVerification
  case smithy.OnboardStage.EmailVerified     => OnboardStage.EmailVerified
  case smithy.OnboardStage.PasswordProvided  => OnboardStage.PasswordProvided
  case smithy.OnboardStage.PhoneVerification => OnboardStage.PhoneVerification
  case smithy.OnboardStage.PhoneVerified     => OnboardStage.PhoneVerified
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
    case CustomerType.Individual => smithy.CustomerType.Individual
    case CustomerType.Business   => smithy.CustomerType.Business
  }

def addressTypeFromDomainToSmithy(addressType: AddressType): smithy.AddressType = addressType match {
  case AddressType.Shipping           => smithy.AddressType.Shipping
  case AddressType.Billing            => smithy.AddressType.Billing
  case AddressType.ShippingAndBilling => smithy.AddressType.ShippingAndBilling
}

def addressTypeFromSmithyToDomain(addressType: smithy.AddressType): AddressType = addressType match {
  case smithy.AddressType.Shipping           => AddressType.Shipping
  case smithy.AddressType.Billing            => AddressType.Billing
  case smithy.AddressType.ShippingAndBilling => AddressType.ShippingAndBilling
}

def organizationUserRoleFromSmithyToDomain(role: smithy.OrganizationUserRole): OrganizationUserRole = role match {
  case smithy.OrganizationUserRole.Owner => OrganizationUserRole.Owner
  case smithy.OrganizationUserRole.Admin => OrganizationUserRole.Admin
  case smithy.OrganizationUserRole.User  => OrganizationUserRole.User
}

def organizationUserRoleFromDomainToSmithy(role: OrganizationUserRole): smithy.OrganizationUserRole = role match {
  case OrganizationUserRole.Owner => smithy.OrganizationUserRole.Owner
  case OrganizationUserRole.Admin => smithy.OrganizationUserRole.Admin
  case OrganizationUserRole.User  => smithy.OrganizationUserRole.User
}

def catalogueItemStatusFromDomainToSmithy(status: CatalogueItemStatus): smithy.CatalogueItemStatus = status match {
  case CatalogueItemStatus.Active   => smithy.CatalogueItemStatus.Active
  case CatalogueItemStatus.Archived => smithy.CatalogueItemStatus.Archived
}

val DevOtp = "123QWE"

def verifyOtpInDev(otp: Otp, isDev: Boolean): Boolean = isDev && otp.value == DevOtp
