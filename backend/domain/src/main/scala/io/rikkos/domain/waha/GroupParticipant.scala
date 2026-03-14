package io.rikkos.domain.waha

case class GroupParticipant(
    userID: UserID,
    userAccountID: UserAccountID,
    role: GroupParticipantRole,
)
