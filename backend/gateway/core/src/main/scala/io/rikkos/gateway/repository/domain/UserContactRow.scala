package io.rikkos.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserContactRow(
    userContactID: UserContactID,
    userID: UserID,
    displayName: DisplayName,
    firstName: FirstName,
    phoneNumber: PhoneNumberE164,
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
