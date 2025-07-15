package io.rikkos.domain

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

import java.time.Instant

type NonEmptyTrimmedLowerCase = Trimmed & LettersLowerCase & MinLength[1]
type NonEmptyTrimmed          = Trimmed & MinLength[1]

object AppName extends RefinedType[String, Pure]
type AppName = AppName.T

object UserID extends RefinedType[String, NonEmptyTrimmed]
type UserID = UserID.T

object UserContactID extends RefinedType[String, NonEmptyTrimmed]
type UserContactID = UserContactID.T

object Email extends RefinedType[String, NonEmptyTrimmedLowerCase]
type Email = Email.T

object FirstName extends RefinedType[String, NonEmptyTrimmed]
type FirstName = FirstName.T

object LastName extends RefinedType[String, NonEmptyTrimmed]
type LastName = LastName.T

object PhoneNumber extends RefinedType[String, NonEmptyTrimmed] {
  def cy(phoneNationalNumber: String): PhoneNumber.T =
    assume(s"+357$phoneNationalNumber")
}
type PhoneNumber = PhoneNumber.T

object AddressLine1 extends RefinedType[String, NonEmptyTrimmed]
type AddressLine1 = AddressLine1.T

object AddressLine2 extends RefinedType[String, NonEmptyTrimmed]
type AddressLine2 = AddressLine2.T

object City extends RefinedType[String, NonEmptyTrimmed]
type City = City.T

object PostalCode extends RefinedType[String, NonEmptyTrimmed]
type PostalCode = PostalCode.T

object Company extends RefinedType[String, NonEmptyTrimmed]
type Company = Company.T

object DisplayName extends RefinedType[String, NonEmptyTrimmed]
type DisplayName = DisplayName.T

object CreatedAt extends RefinedType[Instant, Pure]
type CreatedAt = CreatedAt.T

object UpdatedAt extends RefinedType[Instant, Pure]
type UpdatedAt = UpdatedAt.T
