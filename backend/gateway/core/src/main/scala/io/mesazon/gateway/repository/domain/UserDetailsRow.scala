package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserDetailsRow(
    userID: UserID,
    email: Email,
    fullName: Option[FullName],
    phoneNumber: Option[PhoneNumber],
    onboardStage: OnboardStage,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
