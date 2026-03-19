package io.mesazon.domain.waha

case class GroupParticipant(
    userID: UserID,
    userAccountID: UserAccountID,
    role: GroupParticipantRole,
)
