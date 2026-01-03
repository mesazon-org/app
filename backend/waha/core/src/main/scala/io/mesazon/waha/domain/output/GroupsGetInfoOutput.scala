package io.mesazon.waha.domain.output

import io.mesazon.waha.domain.*

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
