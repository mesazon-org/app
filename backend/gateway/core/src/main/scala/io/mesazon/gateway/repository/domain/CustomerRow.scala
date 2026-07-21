package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CustomerRow(
    organizationID: OrganizationID,
    customerID: CustomerID,
    customerType: CustomerType,
    status: CustomerStatus,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
