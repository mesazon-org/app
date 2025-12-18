package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

case class GroupsAddParticipantsInput(
    sessionID: SessionID,
    groupID: GroupID,
    participants: List[UserAccountID],
)
