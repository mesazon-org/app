package io.rikkos.domain

final case class AuthedUser(
    userID: UserID,
    email: Email,
)
