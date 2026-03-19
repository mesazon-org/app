package io.mesazon.domain.waha.input

import io.mesazon.domain.waha.*

case class GroupsAddParticipantsInput(
    sessionID: SessionID,
    groupID: GroupID,
    participants: List[UserAccountID],
)
