package io.mesazon.domain.waha.input

import io.mesazon.domain.waha.*

case class GroupsInviteCodeRevokeInput(
    sessionID: SessionID,
    groupID: GroupID,
)
