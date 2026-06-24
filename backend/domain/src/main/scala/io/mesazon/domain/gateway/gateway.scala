package io.mesazon.domain.gateway

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.Positive
import io.mesazon.domain.*

import java.time.Instant

object AppName extends RefinedType[String, Pure]
type AppName = AppName.T

object UserID extends RefinedTypeUUID
type UserID = UserID.T

object ActionAttemptID extends RefinedTypeUUID
type ActionAttemptID = ActionAttemptID.T

object TokenID extends RefinedTypeUUID
type TokenID = TokenID.T

object Email extends RefinedType[String, EmailPredicate]
type Email = Email.T

object FullName extends RefinedType[String, NonEmptyTrimmed]
type FullName = FullName.T

object Password extends RefinedType[String, PasswordPredicate]
type Password = Password.T

object PasswordHash extends RefinedType[String, NonEmptyTrimmed]
type PasswordHash = PasswordHash.T

object PhoneRegion extends RefinedType[String, NonEmptyTrimmed]
type PhoneRegion = PhoneRegion.T

object PhoneCountryCode extends RefinedType[String, NonEmptyTrimmed]
type PhoneCountryCode = PhoneCountryCode.T

object PhoneNationalNumber extends RefinedType[String, NonEmptyTrimmed]
type PhoneNationalNumber = PhoneNationalNumber.T

object PhoneNumberE164 extends RefinedType[String, NonEmptyTrimmed]
type PhoneNumberE164 = PhoneNumberE164.T

object Message extends RefinedType[String, NonEmpty]
type Message = Message.T

object Otp extends RefinedType[String, OtpPredicate]
type Otp = Otp.T

object OtpID extends RefinedTypeUUID
type OtpID = OtpID.T

object CreatedAt extends RefinedType[Instant, Pure]
type CreatedAt = CreatedAt.T

object UpdatedAt extends RefinedType[Instant, Pure]
type UpdatedAt = UpdatedAt.T

object ExpiresAt extends RefinedType[Instant, Pure]
type ExpiresAt = ExpiresAt.T

object RefreshToken extends RefinedType[String, NonEmptyTrimmedUnsafe]
type RefreshToken = RefreshToken.T

object ResetPasswordToken extends RefinedType[String, NonEmptyTrimmedUnsafe]
type ResetPasswordToken = ResetPasswordToken.T

object AccessToken extends RefinedType[String, NonEmptyTrimmedUnsafe]
type AccessToken = AccessToken.T

object Attempts extends RefinedType[Int, Positive]
type Attempts = Attempts.T

object OrganizationID extends RefinedTypeUUID
type OrganizationID = OrganizationID.T

// This is the name provided by the user when uploading the logo file.
// Should be used for displaying the logo file name in the UI or when downloading the logo file from s3.
object OrganizationLogoFileName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationLogoFileName = OrganizationLogoFileName.T

// This is the s3 original name which is configured not the one provided by the user.
object OrganizationOriginalLogoFileName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationOriginalLogoFileName = OrganizationLogoFileName.T

object OrganizationNormalizedLogoFileName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationNormalizedLogoFileName = OrganizationNormalizedLogoFileName.T

object OrganizationWhatsAppLogoFileName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationWhatsAppLogoFileName = OrganizationWhatsAppLogoFileName.T

object OrganizationLogoUrl extends RefinedType[String, NonEmptyTrimmedUnsafe]
type OrganizationLogoUrl = OrganizationLogoUrl.T

object OrganizationLogoBucketKey extends RefinedType[String, NonEmptyTrimmedUnsafe]
type OrganizationLogoBucketKey = OrganizationLogoBucketKey.T

object OrganizationName extends RefinedType[String, NonEmptyTrimmed]
type OrganizationName = OrganizationName.T

object OrganizationSlug extends RefinedType[String, NonEmptyTrimmedLowerCase]
type OrganizationSlug = OrganizationSlug.T

object OrganizationEmail extends RefinedType[String, EmailPredicate]
type OrganizationEmail = OrganizationEmail.T

object OrganizationPhoneNumber extends RefinedType[PhoneNumber, Pure]
type OrganizationPhoneNumber = OrganizationPhoneNumber.T

object OrganizationAddressLine1 extends RefinedType[String, NonEmptyTrimmed]
type OrganizationAddressLine1 = OrganizationAddressLine1.T

object OrganizationAddressLine2 extends RefinedType[String, NonEmptyTrimmed]
type OrganizationAddressLine2 = OrganizationAddressLine2.T

object OrganizationCity extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCity = OrganizationCity.T

object OrganizationPostalCode extends RefinedType[String, NonEmptyTrimmed]
type OrganizationPostalCode = OrganizationPostalCode.T

object OrganizationCountry extends RefinedType[String, NonEmptyTrimmed]
type OrganizationCountry = OrganizationCountry.T
