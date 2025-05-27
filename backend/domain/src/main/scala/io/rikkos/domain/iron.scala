package io.rikkos.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

type NonEmptyTrimmedLowerCase = Trimmed & LettersLowerCase & MinLength[1]
type NonEmptyTrimmed          = Trimmed & MinLength[1]

object AppName extends RefinedType[String, Pure]
type AppName = AppName.T

object MemberID extends RefinedType[String, NonEmptyTrimmedLowerCase]
type MemberID = MemberID.T

object Email extends RefinedType[String, NonEmptyTrimmedLowerCase]
type Email = Email.T

object FirstName extends RefinedType[String, NonEmptyTrimmed]
type FirstName = FirstName.T

object LastName extends RefinedType[String, NonEmptyTrimmed]
type LastName = LastName.T

object Organization extends RefinedType[String, NonEmptyTrimmed]
type Organization = Organization.T
