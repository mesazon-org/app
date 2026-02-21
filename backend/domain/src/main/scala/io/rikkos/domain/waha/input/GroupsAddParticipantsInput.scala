package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class GroupsAddParticipantsInput(
    sessionID: SessionID,
    groupID: GroupID,
    participants: List[UserAccountID],
)
