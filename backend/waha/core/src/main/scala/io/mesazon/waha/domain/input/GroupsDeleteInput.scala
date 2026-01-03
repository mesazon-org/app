package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

case class GroupsDeleteInput(
    sessionID: SessionID,
    groupID: GroupID,
)
