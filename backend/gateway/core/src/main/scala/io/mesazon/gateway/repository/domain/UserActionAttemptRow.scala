package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserActionAttemptRow(
    actionAttemptID: ActionAttemptID,
    userID: UserID,
    actionAttemptType: ActionAttemptType,
    attempts: Attempts,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
