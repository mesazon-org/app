package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class GroupsInviteCodeInput(
    sessionID: SessionID,
    groupID: GroupID,
)
