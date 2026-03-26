package io.mesazon.domain.waha.input

import io.mesazon.domain.waha.*

case class GroupsCreateInput(
    sessionID: SessionID,
    name: GroupName,
    description: Option[GroupDescription],
    picture: Option[FileType],
    participants: List[UserAccountID],
)
