package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

case class GroupsGetInfoInput(
    sessionID: SessionID,
    groupID: GroupID,
)
