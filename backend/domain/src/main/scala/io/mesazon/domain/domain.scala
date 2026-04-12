package io.mesazon.domain

import io.github.iltotore.iron.constraint.all.*

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
