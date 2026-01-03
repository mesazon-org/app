package io.rikkos.domain

case class UserContactTable(
    userContactID: UserContactID,
    userID: UserID,
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
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
