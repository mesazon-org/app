package io.rikkos.gateway.repository.domain

import io.rikkos.domain.gateway.{UpdatedAt, UserID}
import io.rikkos.domain.waha

case class WahaUserActivityRow(
    userID: UserID,
    lastMessageID: Option[waha.MessageID],
    isWaitingAssistantReply: Boolean,
    lastUpdate: UpdatedAt,
)
