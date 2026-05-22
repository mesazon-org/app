package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserDetailsRow(
    userID: UserID,
    email: UserEmail,
    fullName: Option[FullName],
    phoneNumber: Option[UserPhoneNumber],
    onboardStage: OnboardStage,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
