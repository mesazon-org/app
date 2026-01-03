package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

case class GroupsInviteCodeRevokeInput(
    sessionID: SessionID,
    groupID: GroupID,
)
