package io.mesazon.domain.gateway

import io.github.iltotore.iron.*
import io.mesazon.domain.*

// Newtypes

object OrganizationID extends RefinedTypeUUID
type OrganizationID = OrganizationID.T

object OrganizationName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationName = OrganizationName.T

object OrganizationSlug extends RefinedType[String, SlugPredicate]
type OrganizationSlug = OrganizationSlug.T

object OrganizationTagline extends RefinedType[String, NonEmptyTrimmed]
type OrganizationTagline = OrganizationTagline.T

object OrganizationEmail extends RefinedType[String, EmailPredicate]
type OrganizationEmail = OrganizationEmail.T

object OrganizationPhoneNumber extends RefinedType[PhoneNumber, Pure]
type OrganizationPhoneNumber = OrganizationPhoneNumber.T

object OrganizationAddressLine1 extends RefinedType[String, NonEmptyTrimmed]
type OrganizationAddressLine1 = OrganizationAddressLine1.T

object OrganizationAddressLine2 extends RefinedType[String, NonEmptyTrimmed]
type OrganizationAddressLine2 = OrganizationAddressLine2.T

object OrganizationCity extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCity = OrganizationCity.T

object OrganizationPostalCode extends RefinedType[String, NonEmptyTrimmed]
type OrganizationPostalCode = OrganizationPostalCode.T

object OrganizationCountry extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCountry = OrganizationCountry.T

object OrganizationCompanyRegistrationNumber extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCompanyRegistrationNumber = OrganizationCompanyRegistrationNumber.T

object OrganizationTaxID extends RefinedType[String, NonEmptyTrimmed]
type OrganizationTaxID = OrganizationTaxID.T

object OrganizationLogoOriginalFileName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationLogoOriginalFileName = OrganizationLogoOriginalFileName.T

object OrganizationLogoOriginalUrl extends RefinedType[String, NonEmptyTrimmedUnsafe]
type OrganizationLogoOriginalUrl = OrganizationLogoOriginalUrl.T

object OrganizationLogoNormalizedUrl extends RefinedType[String, NonEmptyTrimmedUnsafe]
type OrganizationLogoNormalizedUrl = OrganizationLogoNormalizedUrl.T

object OrganizationLogoOriginalBucketKey extends RefinedType[String, NonEmptyTrimmedUnsafe]
type OrganizationLogoOriginalBucketKey = OrganizationLogoOriginalBucketKey.T

object OrganizationLogoNormalizedBucketKey extends RefinedType[String, NonEmptyTrimmedUnsafe]
type OrganizationLogoNormalizedBucketKey = OrganizationLogoNormalizedBucketKey.T

// Enums

enum OrganizationStage {
  case DetailsProvided
  case LogoProvided
}

enum OrganizationUserRole {
  case Owner
  case Admin
  case User
}

object OrganizationUserRole {
  val ownerRoles: List[OrganizationUserRole] = List(OrganizationUserRole.Owner)
  val adminRoles: List[OrganizationUserRole] = List(OrganizationUserRole.Owner, OrganizationUserRole.Admin)
  val userRoles: List[OrganizationUserRole]  =
    List(OrganizationUserRole.Owner, OrganizationUserRole.Admin, OrganizationUserRole.User)
}

// Contact points (each carries an isDefault flag)

case class OrganizationEmailEntry(
    email: OrganizationEmail,
    isDefault: Boolean,
)

case class OrganizationPhoneNumberEntry(
    phoneNumber: OrganizationPhoneNumber,
    isDefault: Boolean,
)

// Requests

case class CreateOrganization(
    name: OrganizationName,
    slug: OrganizationSlug,
    tagline: Option[OrganizationTagline],
    emails: List[OrganizationEmailEntry],
    phoneNumbers: List[OrganizationPhoneNumberEntry],
    addressLine1: Option[OrganizationAddressLine1],
    addressLine2: Option[OrganizationAddressLine2],
    city: Option[OrganizationCity],
    postalCode: Option[OrganizationPostalCode],
    country: Option[OrganizationCountry],
    companyRegistrationNumber: Option[OrganizationCompanyRegistrationNumber],
    taxID: Option[OrganizationTaxID],
)
