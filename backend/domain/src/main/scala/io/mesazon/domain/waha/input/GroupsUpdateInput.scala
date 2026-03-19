package io.mesazon.domain.waha.input

import io.mesazon.domain.waha.*

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
