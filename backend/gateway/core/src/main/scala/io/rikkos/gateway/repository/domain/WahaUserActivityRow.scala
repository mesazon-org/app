package io.rikkos.gateway.repository.domain

import io.rikkos.domain.gateway.{UpdatedAt, UserID}

case class WahaUserActivityRow(
    userID: UserID,
    isWaitingAssistantReply: Boolean,
    lastUpdate: UpdatedAt,
)
