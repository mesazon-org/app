package io.mesazon.domain.gateway

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import io.mesazon.domain.*

import java.time.Instant

object AppName extends RefinedType[String, Pure]
type AppName = AppName.T

object UserID extends RefinedType[String, NonEmptyTrimmed]
type UserID = UserID.T

object TokenID extends RefinedType[String, NonEmptyTrimmed]
type TokenID = TokenID.T

object UserContactID extends RefinedType[String, NonEmptyTrimmed]
type UserContactID = UserContactID.T

object Email extends RefinedType[String, EmailPredicate]
type Email = Email.T

object FullName extends RefinedType[String, NonEmptyTrimmed]
type FullName = FullName.T

object Password extends RefinedType[String, PasswordPredicate]
type Password = Password.T

object PasswordHash extends RefinedType[String, NonEmptyTrimmed]
type PasswordHash = PasswordHash.T

object PhoneNumberE164 extends RefinedType[String, NonEmptyTrimmed] {
  def cy(phoneNationalNumber: String): PhoneNumberE164.T =
    assume(s"+357$phoneNationalNumber")
}
type PhoneNumberE164 = PhoneNumberE164.T

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

object Message extends RefinedType[String, NonEmpty]
type Message = Message.T

object Otp extends RefinedType[String, OtpPredicate]
type Otp = Otp.T

object Retries extends RefinedType[Int, Positive]
type Retries = Retries.T

object OtpID extends RefinedType[String, NonEmptyTrimmed]
type OtpID = OtpID.T

object CreatedAt extends RefinedType[Instant, Pure]
type CreatedAt = CreatedAt.T

object UpdatedAt extends RefinedType[Instant, Pure]
type UpdatedAt = UpdatedAt.T

object ExpiresAt extends RefinedType[Instant, Pure]
type ExpiresAt = ExpiresAt.T

object RefreshToken extends RefinedType[String, NonEmptyTrimmed]
type RefreshToken = RefreshToken.T

object AccessToken extends RefinedType[String, NonEmptyTrimmed]
type AccessToken = AccessToken.T
