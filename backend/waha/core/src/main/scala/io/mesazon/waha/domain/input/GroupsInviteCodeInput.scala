package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

case class GroupsInviteCodeInput(
    sessionID: SessionID,
    groupID: GroupID,
)
