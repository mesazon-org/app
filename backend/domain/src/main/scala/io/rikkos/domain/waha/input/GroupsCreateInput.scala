package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class GroupsCreateInput(
    sessionID: SessionID,
    name: GroupName,
    description: Option[GroupDescription],
    picture: Option[FileType],
    participants: List[UserAccountID],
)
