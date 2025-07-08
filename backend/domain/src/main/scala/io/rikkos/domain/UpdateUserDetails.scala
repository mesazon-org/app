package io.rikkos.domain

final case class UpdateUserDetails(
    firstName: Option[FirstName],
    lastName: Option[LastName],
    phoneRegion: Option[PhoneRegion],
    phoneNationalNumber: Option[PhoneNationalNumber],
    addressLine1: Option[AddressLine1],
    addressLine2: Option[AddressLine2],
    city: Option[City],
    postalCode: Option[PostalCode],
    company: Option[Company],
) {
  def allEmpty: Boolean =
    firstName.isEmpty &&
      lastName.isEmpty &&
      phoneRegion.isEmpty &&
      phoneNationalNumber.isEmpty &&
      addressLine1.isEmpty &&
      addressLine2.isEmpty &&
      city.isEmpty &&
      postalCode.isEmpty &&
      company.isEmpty
}
