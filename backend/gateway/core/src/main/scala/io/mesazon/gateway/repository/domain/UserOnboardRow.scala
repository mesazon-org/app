package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

final case class UserOnboardRow(
    userID: UserID,
    email: Email,
    fullName: Option[FullName],
    passwordHash: Option[PasswordHash],
    phoneNumber: Option[PhoneNumberE164],
    stage: OnboardStage,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
