package io.mesazon.waha.domain

case class GroupParticipant(
    userID: UserID,
    userAccountID: UserAccountID,
    role: GroupParticipantRole,
)
