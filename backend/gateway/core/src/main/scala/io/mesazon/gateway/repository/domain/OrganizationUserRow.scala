package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class OrganizationUserRow(
    organizationID: OrganizationID,
    userID: UserID,
    userRole: UserRole,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
