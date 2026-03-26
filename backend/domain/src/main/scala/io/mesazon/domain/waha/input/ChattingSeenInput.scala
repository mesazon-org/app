package io.mesazon.domain.waha.input

import io.mesazon.domain.waha.*

case class ChattingSeenInput(
    sessionID: SessionID,
    chatID: ChatID,
    messageIDs: List[MessageID],
)
