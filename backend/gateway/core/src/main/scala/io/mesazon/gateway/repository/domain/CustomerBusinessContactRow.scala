package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CustomerBusinessContactRow(
    organizationID: OrganizationID,
    customerID: CustomerID,
    customerBusinessContactID: CustomerBusinessContactID,
    fullName: CustomerFullName,
    role: Option[CustomerBusinessContactRole],
    email: Option[CustomerEmail],
    phoneNumber: Option[CustomerPhoneNumber],
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
