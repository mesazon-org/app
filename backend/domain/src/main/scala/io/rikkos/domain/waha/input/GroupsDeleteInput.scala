package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class GroupsDeleteInput(
    sessionID: SessionID,
    groupID: GroupID,
)
