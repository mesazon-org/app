package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.OtpGenerator
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.mesazon.generator.IDGenerator
import zio.*

import java.time.Instant

object AuthenticationService {

  private final class AuthenticationServiceImpl(
      authenticationConfig: AuthenticationConfig,
      userManagementRepository: UserManagementRepository,
      emailValidator: ServiceValidator[EmailRaw, Email],
      timeProvider: TimeProvider,
      emailClient: EmailClient,
      otpGenerator: OtpGenerator,
      idGenerator: IDGenerator,
  ) extends smithy.AuthenticationService[ServiceTask] {

    private def userReSignupEmailCondition(
        maybeUserOnboardRow: Option[UserOnboardRow],
        maybeUserOtpRow: Option[UserOtpRow],
        now: Instant,
    ): Boolean = {
      val firstTimeRegister   = maybeUserOnboardRow.isEmpty || maybeUserOtpRow.isEmpty
      val otpExpiringCooldown =
        maybeUserOtpRow.exists(
          _.expiresAt.value.minusSeconds(authenticationConfig.otpResendCooldown.toSeconds).isBefore(now)
        )
      val inEmailConfirmationStage = maybeUserOnboardRow.exists(_.stage == OnboardStage.EmailConfirmation)
      val inEmailConfirmedStage    = maybeUserOnboardRow.exists(_.stage == OnboardStage.EmailConfirmed)

      firstTimeRegister || (otpExpiringCooldown && (inEmailConfirmationStage || inEmailConfirmedStage))
    }

    private def userReSignupUseSameOtpCondition(
        maybeUserOnboardRow: Option[UserOnboardRow],
        userOtpRow: UserOtpRow,
        now: Instant,
    ): Boolean = {
      val headroomSeconds          = 2
      val inEmailConfirmationStage = maybeUserOnboardRow.exists(_.stage == OnboardStage.EmailConfirmation)
      val inEmailConfirmedStage    = maybeUserOnboardRow.exists(_.stage == OnboardStage.EmailConfirmed)
      val userOtpNotExpiring       =
        userOtpRow.expiresAt.value
          .minusSeconds(authenticationConfig.otpResendCooldown.toSeconds - headroomSeconds)
          .isAfter(now)
      userOtpNotExpiring && (inEmailConfirmationStage || inEmailConfirmedStage)
    }

    /** HTTP POST /signup/email */
    override def signUpEmail(request: smithy.SignUpEmailRequest): ServiceTask[smithy.SignUpEmailResponse] = for {
      _                   <- ZIO.logDebug(s"Signing up user with email: ${request.email}")
      email               <- emailValidator.validate(request.email)
      maybeUserOnboardRow <- userManagementRepository.getUserOnboardByEmail(email)
      maybeUserOtpRow     <- ZIO
        .foreach(maybeUserOnboardRow)(userOnboardRow =>
          userManagementRepository.getUserOtpByUserID(
            userOnboardRow.userID,
            OtpType.EmailVerification,
          )
        )
        .map(_.flatten)
      now   <- timeProvider.instantNow
      otpID <-
        if (userReSignupEmailCondition(maybeUserOnboardRow, maybeUserOtpRow, now)) {
          for {
            newUserOnboardRow <- maybeUserOnboardRow.fold(
              userManagementRepository.insertUserOnboardEmail(email, OnboardStage.EmailConfirmation)
            )(existingUserOnboardRow =>
              userManagementRepository.updateUserOnboard(existingUserOnboardRow.userID, OnboardStage.EmailConfirmation)
            )
            expiresAt <- timeProvider.instantNow
              .map(_.plusSeconds(authenticationConfig.otpExpiration.toSeconds))
              .map(ExpiresAt.assume)
            otp        <- otpGenerator.generate
            newUserOtp <- userManagementRepository.upsertUserOtp(
              newUserOnboardRow.userID,
              otp,
              OtpType.EmailVerification,
              expiresAt,
            )
            _ <- emailClient
              .sendEmailVerificationEmail(newUserOnboardRow.email, otp)
              .retry(
                Schedule.recurs(authenticationConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                  .exponential(authenticationConfig.sendEmailVerificationEmailRetryDelay)
              )
          } yield newUserOtp.otpID
        } else {
          maybeUserOtpRow match {
            case Some(userOtpRow) if userReSignupUseSameOtpCondition(maybeUserOnboardRow, userOtpRow, now) =>
              ZIO.succeed(userOtpRow.otpID)
            case _ => idGenerator.generate.map(OtpID.assume)
          }
        }
    } yield smithy.SignUpEmailResponse(otpID.value)
  }

  private def observed(
      service: smithy.AuthenticationService[ServiceTask]
  ): smithy.AuthenticationService[Task] =
    new smithy.AuthenticationService[Task] {

      /** HTTP POST /signup/email */
      override def signUpEmail(request: smithy.SignUpEmailRequest): Task[smithy.SignUpEmailResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpEmail(request))
    }

  val live = ZLayer.derive[AuthenticationServiceImpl] >>> ZLayer.fromFunction(observed)
}
