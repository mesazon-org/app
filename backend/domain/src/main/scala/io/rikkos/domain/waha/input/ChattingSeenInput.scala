package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class ChattingSeenInput(
    sessionID: SessionID,
    chatID: ChatID,
    messageIDs: List[MessageID],
)
