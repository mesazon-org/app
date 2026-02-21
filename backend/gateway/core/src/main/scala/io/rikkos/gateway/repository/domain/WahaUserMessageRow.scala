package io.rikkos.gateway.repository.domain

import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha

case class WahaUserMessageRow(
    userID: UserID,
    messageID: waha.MessageID,
    message: String,
    isAssistant: Boolean,
    createdAt: CreatedAt,
)
