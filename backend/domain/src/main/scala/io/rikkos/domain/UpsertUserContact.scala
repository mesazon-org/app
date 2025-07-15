package io.rikkos.domain

final case class UpsertUserContact(
    userContactID: Option[UserContactID],
    displayName: DisplayName,
    firstName: FirstName,
    phoneNumber: PhoneNumber,
    lastName: Option[LastName],
    email: Option[Email],
    addressLine1: Option[AddressLine1],
    addressLine2: Option[AddressLine2],
    city: Option[City],
    postalCode: Option[PostalCode],
    company: Option[Company],
)
