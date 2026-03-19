package io.rikkos.gateway.repository.domain

import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha

case class WahaUserRow(
    userID: UserID,
    fullName: waha.FullName,
    wahaUserID: waha.UserID,
    wahaAccountID: waha.UserAccountID,
    wahaChatID: waha.ChatID,
    phoneNumber: PhoneNumberE164,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
