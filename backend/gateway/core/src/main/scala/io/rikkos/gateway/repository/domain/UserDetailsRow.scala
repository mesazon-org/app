package io.rikkos.gateway.repository.domain
import io.mesazon.domain.gateway.*

case class UserDetailsRow(
    userID: UserID,
    email: Email,
    firstName: FirstName,
    lastName: LastName,
    phoneNumber: PhoneNumberE164,
    addressLine1: AddressLine1,
    addressLine2: Option[AddressLine2],
    city: City,
    postalCode: PostalCode,
    company: Company,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
