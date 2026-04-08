package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserTokenRow(
    tokenID: TokenID,
    userID: UserID,
    tokenType: TokenType,
    createdAt: CreatedAt,
    expiresAt: ExpiresAt,
)
