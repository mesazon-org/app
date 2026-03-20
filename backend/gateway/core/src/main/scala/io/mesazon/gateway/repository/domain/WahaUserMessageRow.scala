package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha

case class WahaUserMessageRow(
    userID: UserID,
    messageID: waha.MessageID,
    message: waha.MessageText,
    isAssistant: Boolean,
    createdAt: CreatedAt,
)
