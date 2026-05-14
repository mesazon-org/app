package io.mesazon.domain

import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.constraint.all.*

import java.util.UUID
import scala.util.control.Exception.allCatch

type NonEmptyTrimmedLowerCase = Trimmed & LettersLowerCase & MinLength[1]
type NonEmptyTrimmed          = Trimmed & MinLength[1]
type NonEmpty                 = MinLength[1]

type PasswordPredicate =
  Match["^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%#*^,?)(&._-])[A-Za-z\\d@$!%#*^,?)(&._-]{8,72}$"]
type EmailPredicate       = Match["^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"]
type OtpPredicate         = Match["^[A-Z0-9]{6}$"]
type WahaIDPredicate      = NonEmptyTrimmedLowerCase & EndWith["@c.us"]
type WahaGroupIDPredicate = NonEmptyTrimmedLowerCase & EndWith["@g.us"]
type WahaUserIDPredicate  = NonEmptyTrimmedLowerCase & EndWith["@lid"]
type WhatsappIDPredicate  = NonEmptyTrimmedLowerCase & EndWith["@s.whatsapp.net"]

trait RefinedTypeUUID extends RefinedType[UUID, Pure] {
  def eitherFromString(s: String): Either[String, T] =
    allCatch.either(apply(UUID.fromString(s))).left.map(error => s"Invalid UUID format error: [${error.getMessage}]")

  def randomUUID: T = apply(UUID.randomUUID())
}
