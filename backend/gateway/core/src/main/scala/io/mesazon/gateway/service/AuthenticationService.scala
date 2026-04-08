package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.{JwtService, OtpGenerator}
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.AuthenticationConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{HttpErrorHandler, smithy}
import io.mesazon.generator.IDGenerator
import zio.*

object AuthenticationService {

  private final class AuthenticationServiceImpl(
      authenticationConfig: AuthenticationConfig,
      userOtpRepository: UserOtpRepository,
      userManagementRepository: UserManagementRepository,
      emailValidator: ServiceValidator[EmailRaw, Email],
      verifyEmailValidator: ServiceValidator[smithy.VerifyEmailRequest, VerifyEmail],
      jwtService: JwtService,
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
          userOtpRepository.getUserOtpByUserID(
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
              OnboardStage.EmailVerification,
            )
            newOtp <-
              if (isEmptyOrExpiredOrExpiringSoon) otpGenerator.generate
              else maybeUserOtpRow.map(_.otp).fold(otpGenerator.generate)(ZIO.succeed(_))
            newExpiresAt <-
              timeProvider.instantNow
                .map(_.plusSeconds(authenticationConfig.otpExpiration.toSeconds))
                .map(ExpiresAt.assume)
            newUserOtpRow <- userOtpRepository.upsertUserOtp(
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
            newUserOnboardRow <- userManagementRepository.insertUserOnboardEmail(email, OnboardStage.EmailVerification)
            expiresAt         <- timeProvider.instantNow
              .map(_.plusSeconds(authenticationConfig.otpExpiration.toSeconds))
              .map(ExpiresAt.assume)
            otp           <- otpGenerator.generate
            newUserOtpRow <- userOtpRepository.upsertUserOtp(
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

    /** HTTP POST /verify/email */
    override def verifyEmail(request: smithy.VerifyEmailRequest): ServiceTask[smithy.VerifyEmailResponse] = for {
      _               <- ZIO.logDebug(s"Verify email: [${request.otpID}]")
      verifyEmail     <- verifyEmailValidator.validate(request)
      maybeUserOtpRow <- userOtpRepository.getUserOtp(verifyEmail.otpID, OtpType.EmailVerification)
      userOtpRow      <- ZIO.getOrFailWith(
        ServiceError.UnauthorizedError.OtpError(s"No otp found for otpID: ${verifyEmail.otpID}")
      )(maybeUserOtpRow)
      maybeUserOnboardRow <- userManagementRepository.getUserOnboard(userOtpRow.userID)
      userOnboardRow      <- ZIO.getOrFailWith(
        ServiceError.InternalServerError.UnexpectedError(
          s"No user onboard found for userID: ${userOtpRow.userID} and otpID: ${userOtpRow.otpID}"
        )
      )(maybeUserOnboardRow)
      now <- timeProvider.instantNow
      _   <- (userOtpRow.otpType, userOnboardRow.stage) match {
        case (OtpType.EmailVerification, OnboardStage.EmailVerification)
            if userOtpRow.otp == verifyEmail.otp && userOtpRow.expiresAt.value.isAfter(now) =>
          userManagementRepository
            .updateUserOnboard(
              userOnboardRow.userID,
              OnboardStage.EmailVerified,
            )
            .unit *> userOtpRepository.deleteUserOtp(userOtpRow.otpID, userOtpRow.userID, userOtpRow.otpType)
        case _ =>
          ZIO.fail(
            ServiceError.UnauthorizedError.OtpError(
              s"Invalid otp type: [${userOtpRow.otpType}], expected: [${OtpType.EmailVerification}]"
            )
          )
      }
      accessToken  <- jwtService.generateAccessToken(userOtpRow.userID, OnboardStage.EmailVerified)
      refreshToken <- jwtService.generateRefreshToken(userOtpRow.userID)
      _            <- userManagementRepository.upsertUserRefreshToken(
        userOtpRow.userID,
        refreshToken.tokenID,
        refreshToken.expiresAt,
        None,
      )
    } yield smithy.VerifyEmailResponse(
      expiresInSeconds = accessToken.expiresIn.toSeconds,
      onboardStage = onboardStageFromDomainToSmithy(OnboardStage.EmailVerified),
      refreshToken = refreshToken.jwt.value,
      accessToken = accessToken.jwt.value,
    )
  }

  private def observed(
      service: smithy.AuthenticationService[ServiceTask]
  ): smithy.AuthenticationService[Task] =
    new smithy.AuthenticationService[Task] {

      /** HTTP POST /signup/email */
      override def signUpEmail(request: smithy.SignUpEmailRequest): Task[smithy.SignUpEmailResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpEmail(request))

      /** HTTP POST /verify/email */
      override def verifyEmail(request: smithy.VerifyEmailRequest): Task[smithy.VerifyEmailResponse] = HttpErrorHandler
        .errorResponseHandler(service.verifyEmail(request))
    }

  val live = ZLayer.derive[AuthenticationServiceImpl] >>> ZLayer.fromFunction(observed)
}
