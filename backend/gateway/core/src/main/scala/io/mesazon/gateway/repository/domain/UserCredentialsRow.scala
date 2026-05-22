package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserCredentialsRow(
    userID: UserID,
    passwordHash: PasswordHash,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
