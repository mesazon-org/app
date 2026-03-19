package io.mesazon.domain.waha.output

import io.mesazon.domain.waha.*

case class GroupsGetInfoOutput(
    groupID: GroupID,
    name: GroupName,
    ownerUserID: UserID,
    ownerUserAccountID: UserAccountID,
    ownerPhoneNumber: WhatsAppPhoneNumber,
    description: Option[GroupDescription],
    pictureUrl: Option[GroupPictureUrl],
    participants: List[GroupParticipant],
)
