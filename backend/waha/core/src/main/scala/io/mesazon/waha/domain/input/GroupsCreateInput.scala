package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

case class GroupsCreateInput(
    sessionID: SessionID,
    name: GroupName,
    description: Option[GroupDescription],
    picture: Option[FileType],
    participants: List[UserAccountID],
)
