package io.rikkos.gateway.repository.domain

import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha

case class WahaUserRow(
    userID: UserID,
    wahaUserID: waha.UserID,
    wahaAccountID: waha.UserAccountID,
    wahaChatID: waha.ChatID,
    phoneNumber: PhoneNumber,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
