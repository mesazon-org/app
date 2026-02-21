package io.rikkos.domain.waha.output

import io.rikkos.domain.waha.*

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
