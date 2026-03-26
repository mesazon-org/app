package io.mesazon.domain.waha.input

import io.mesazon.domain.waha.*

case class GroupsDeleteInput(
    sessionID: SessionID,
    groupID: GroupID,
)
