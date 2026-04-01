package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class UserOtpRow(
    otpID: OtpID,
    userID: UserID,
    otp: Otp,
    otpType: OtpType,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
    expiresAt: ExpiresAt,
)
