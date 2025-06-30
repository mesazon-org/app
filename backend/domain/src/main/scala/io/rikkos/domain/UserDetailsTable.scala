package io.rikkos.domain

final case class UserDetailsTable(
    userID: UserID,
    email: Email,
    firstName: FirstName,
    lastName: LastName,
    phoneNumber: PhoneNumber,
    addressLine1: AddressLine1,
    addressLine2: Option[AddressLine2],
    city: City,
    postalCode: PostalCode,
    company: Company,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
