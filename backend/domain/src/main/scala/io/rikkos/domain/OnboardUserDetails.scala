package io.rikkos.domain

final case class OnboardUserDetails(
    firstName: FirstName,
    lastName: LastName,
    phoneNumber: PhoneNumber,
    addressLine1: AddressLine1,
    addressLine2: Option[AddressLine2],
    city: City,
    postalCode: PostalCode,
    company: Company,
)
