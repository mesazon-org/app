package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserRefreshTokenRow(
    tokenID: TokenID,
    userID: UserID,
    createdAt: CreatedAt,
    expiresAt: ExpiresAt,
)
