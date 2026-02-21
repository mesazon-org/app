package io.rikkos

import io.github.iltotore.iron.constraint.all.*

package object domain {
  type NonEmptyTrimmedLowerCase = Trimmed & LettersLowerCase & MinLength[1]
  type NonEmptyTrimmed          = Trimmed & MinLength[1]

  type WahaIDPredicate      = NonEmptyTrimmedLowerCase & EndWith["@c.us"]
  type WahaGroupIDPredicate = NonEmptyTrimmedLowerCase & EndWith["@g.us"]
  type WahaUserIDPredicate  = NonEmptyTrimmedLowerCase & EndWith["@lid"]
  type WhatsappIDPredicate  = NonEmptyTrimmedLowerCase & EndWith["@s.whatsapp.net"]
}
