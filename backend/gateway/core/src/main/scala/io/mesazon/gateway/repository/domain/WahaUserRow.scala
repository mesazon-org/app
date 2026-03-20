package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha

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
