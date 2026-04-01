package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.OtpGenerator
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.UserManagementRepository
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.mesazon.generator.IDGenerator
import zio.*

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
      (otpID, otpExpiresAt) <- maybeUserOnboardRow match {
        case Some(existingUserOnboardRow) if OnboardStage.signupEmailStages.contains(existingUserOnboardRow.stage) =>
          for {
            now <- timeProvider.instantNow
            isEmptyOrExpiredOrExpiringSoon = maybeUserOtpRow.forall(
              _.expiresAt.value
                .minusSeconds(authenticationConfig.otpResendCooldown.toSeconds)
                .isBefore(now)
            )
            updatedUserOnboard <- userManagementRepository.updateUserOnboard(
              existingUserOnboardRow.userID,
              OnboardStage.EmailConfirmation,
            )
            newOtp <-
              if (isEmptyOrExpiredOrExpiringSoon) otpGenerator.generate
              else maybeUserOtpRow.map(_.otp).fold(otpGenerator.generate)(ZIO.succeed(_))
            newExpiresAt <-
              timeProvider.instantNow
                .map(_.plusSeconds(authenticationConfig.otpExpiration.toSeconds))
                .map(ExpiresAt.assume)
            newUserOtpRow <- userManagementRepository.upsertUserOtp(
              updatedUserOnboard.userID,
              newOtp,
              OtpType.EmailVerification,
              newExpiresAt,
            )
            _ <-
              if (isEmptyOrExpiredOrExpiringSoon)
                emailClient
                  .sendEmailVerificationEmail(updatedUserOnboard.email, newOtp)
                  .retry(
                    Schedule.recurs(authenticationConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                      .exponential(authenticationConfig.sendEmailVerificationEmailRetryDelay)
                  )
              else ZIO.unit
          } yield (newUserOtpRow.otpID, newUserOtpRow.expiresAt)
        case None =>
          for {
            newUserOnboardRow <- userManagementRepository.insertUserOnboardEmail(email, OnboardStage.EmailConfirmation)
            expiresAt         <- timeProvider.instantNow
              .map(_.plusSeconds(authenticationConfig.otpExpiration.toSeconds))
              .map(ExpiresAt.assume)
            otp        <- otpGenerator.generate
            newUserOtpRow <- userManagementRepository.upsertUserOtp(
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
          } yield (newUserOtpRow.otpID, newUserOtpRow.expiresAt)
        case _ =>
          for {
            otpID     <- idGenerator.generate.map(OtpID.assume)
            expiresAt <- timeProvider.instantNow
              .map(_.plusSeconds(authenticationConfig.otpExpiration.toSeconds))
              .map(ExpiresAt.assume)
          } yield (otpID, expiresAt)
      }
      now                 <- timeProvider.instantNow
      otpExpiresInSeconds <- ZIO
        .attempt(otpExpiresAt.value.getEpochSecond - now.getEpochSecond)
        .mapError(e =>
          ServiceError.InternalServerError.UnexpectedError(
            s"Failed to calculate otpExpiresInSeconds for otpID: [${otpID.value}]",
            Some(e),
          )
        )
    } yield smithy.SignUpEmailResponse(otpID.value, otpExpiresInSeconds)
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
