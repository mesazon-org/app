package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class GroupsInviteCodeRevokeInput(
    sessionID: SessionID,
    groupID: GroupID,
)
