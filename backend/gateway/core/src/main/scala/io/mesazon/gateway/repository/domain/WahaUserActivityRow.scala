package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.{UpdatedAt, UserID}
import io.mesazon.domain.waha

case class WahaUserActivityRow(
    userID: UserID,
    lastMessageID: Option[waha.MessageID],
    isWaitingAssistantReply: Boolean,
    lastUpdate: UpdatedAt,
)
