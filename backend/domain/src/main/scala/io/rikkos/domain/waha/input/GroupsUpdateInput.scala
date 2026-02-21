package io.rikkos.domain.waha.input

import io.rikkos.domain.waha.*

case class GroupsUpdateInput(
    sessionID: SessionID,
    groupID: GroupID,
    name: Option[GroupName],
    description: Option[GroupDescription],
    picture: Option[FileType],
    addParticipants: List[UserAccountID],
    removeParticipants: List[UserAccountID],
    promoteParticipants: List[UserAccountID],
    demoteParticipants: List[UserAccountID],
)
