package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

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
