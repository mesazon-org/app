package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.{JwtService, OtpGenerator}
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.UserSignupConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.mesazon.generator.IDGenerator
import zio.*

object UserSignupService {

  private final class UserSignupServiceImpl(
      userSignupConfig: UserSignupConfig,
      userOtpRepository: UserOtpRepository,
      userTokenRepository: UserTokenRepository,
      userDetailsRepository: UserDetailsRepository,
      jwtService: JwtService,
      timeProvider: TimeProvider,
      emailClient: EmailClient,
      otpGenerator: OtpGenerator,
      idGenerator: IDGenerator,
      verifyEmailValidator: ServiceValidator[smithy.VerifyEmailRequest, VerifyEmail],
      emailValidator: ServiceValidator[EmailRaw, Email],
  ) extends smithy.UserSignupService[ServiceTask] {

    /** HTTP POST /signup/email */
    override def signUpEmail(request: smithy.SignUpEmailRequest): ServiceTask[smithy.SignUpEmailResponse] =
      for {
        _                 <- ZIO.logDebug(s"Signing up user with email: ${request.email}")
        email             <- emailValidator.validate(request.email)
        userDetailsRowOpt <- userDetailsRepository.getUserDetailsByEmail(email)
        userOtpRowOpt     <- ZIO
          .foreach(userDetailsRowOpt)(userDetailsRow =>
            userOtpRepository.getUserOtpByUserID(
              userDetailsRow.userID,
              OtpType.EmailVerification,
            )
          )
          .map(_.flatten)
        otpID <- userDetailsRowOpt match {
          case Some(userDetailsRowExisting)
              if OnboardStage.signupEmailStages.contains(userDetailsRowExisting.onboardStage) =>
            for {
              instantNow <- timeProvider.instantNow
              isEmptyOrExpiredOrExpiringSoon = userOtpRowOpt.forall(
                _.expiresAt.value
                  .minusSeconds(userSignupConfig.otpEmailVerificationResendCooldown.toSeconds)
                  .isBefore(instantNow)
              )
              userDetailsRowUpdated <- userDetailsRepository.updateUserDetails(
                userDetailsRowExisting.userID,
                OnboardStage.EmailVerification,
              )
              otpNew <-
                if (isEmptyOrExpiredOrExpiringSoon) otpGenerator.generate
                else userOtpRowOpt.map(_.otp).fold(otpGenerator.generate)(ZIO.succeed(_))
              expiresAtNew <-
                timeProvider.instantNow
                  .map(_.plusSeconds(userSignupConfig.otpEmailVerificationExpiresAtOffset.toSeconds))
                  .map(ExpiresAt.assume)
              userOtpRowNew <- userOtpRepository.upsertUserOtp(
                userDetailsRowUpdated.userID,
                OtpType.EmailVerification,
                otpNew,
                expiresAtNew,
              )
              _ <- ZIO.whenDiscard(isEmptyOrExpiredOrExpiringSoon)(
                emailClient
                  .sendEmailVerificationEmail(userDetailsRowUpdated.email, otpNew)
                  .retry(
                    Schedule.recurs(userSignupConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                      .exponential(userSignupConfig.sendEmailVerificationEmailRetryDelay)
                  )
              )
            } yield userOtpRowNew.otpID
          case None =>
            for {
              userDetailsRowNew <- userDetailsRepository
                .insertUserDetails(email, OnboardStage.EmailVerification)
              expiresAtNew <- timeProvider.instantNow
                .map(_.plusSeconds(userSignupConfig.otpEmailVerificationExpiresAtOffset.toSeconds))
                .map(ExpiresAt.assume)
              otpNew        <- otpGenerator.generate
              userOtpRowNew <- userOtpRepository.upsertUserOtp(
                userDetailsRowNew.userID,
                OtpType.EmailVerification,
                otpNew,
                expiresAtNew,
              )
              _ <- emailClient
                .sendEmailVerificationEmail(userDetailsRowNew.email, otpNew)
                .retry(
                  Schedule.recurs(userSignupConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                    .exponential(userSignupConfig.sendEmailVerificationEmailRetryDelay)
                )
            } yield userOtpRowNew.otpID
          case _ => idGenerator.generate.map(OtpID.assume)
        }
      } yield smithy.SignUpEmailResponse(otpID.value, userSignupConfig.otpEmailVerificationExpiresAtOffset.toSeconds)

    /** HTTP POST /verify/email */
    override def verifyEmail(request: smithy.VerifyEmailRequest): ServiceTask[smithy.VerifyEmailResponse] =
      for {
        _                  <- ZIO.logDebug(s"Verify email: [${request.otpID}]")
        verifyEmail        <- verifyEmailValidator.validate(request)
        userOtpRowOpt      <- userOtpRepository.getUserOtp(verifyEmail.otpID, OtpType.EmailVerification)
        userOtpRowExisting <- ZIO.getOrFailWith(
          ServiceError.BadRequestError.OtpValidationError(s"No otp found for otpID: ${verifyEmail.otpID}")
        )(userOtpRowOpt)
        userDetailsRowOpt      <- userDetailsRepository.getUserDetails(userOtpRowExisting.userID)
        userDetailsRowExisting <- ZIO.getOrFailWith(
          ServiceError.InternalServerError.UnexpectedError(
            s"No user details found for userID: ${userOtpRowExisting.userID} and otpID: ${userOtpRowExisting.otpID}"
          )
        )(userDetailsRowOpt)
        instantNow <- timeProvider.instantNow
        _          <- (userOtpRowExisting.otpType, userDetailsRowExisting.onboardStage) match {
          case (OtpType.EmailVerification, OnboardStage.EmailVerification)
              if userOtpRowExisting.otp == verifyEmail.otp && userOtpRowExisting.expiresAt.value.isAfter(instantNow) =>
            userDetailsRepository
              .updateUserDetails(
                userDetailsRowExisting.userID,
                OnboardStage.EmailVerified,
              ) *> userOtpRepository.deleteUserOtp(
              userOtpRowExisting.otpID,
              userOtpRowExisting.userID,
              userOtpRowExisting.otpType,
            )
          case _ =>
            ZIO.fail(
              ServiceError.BadRequestError.OtpValidationError(
                s"Invalid otp for otpID: [${userOtpRowExisting.otpID}]"
              )
            )
        }
        _            <- userTokenRepository.deleteAllUserTokens(userDetailsRowExisting.userID)
        accessToken  <- jwtService.generateAccessToken(userDetailsRowExisting.userID)
        refreshToken <- jwtService.generateRefreshToken(userDetailsRowExisting.userID)
        _            <- userTokenRepository.upsertUserToken(
          refreshToken.tokenID,
          userDetailsRowExisting.userID,
          TokenType.RefreshToken,
          refreshToken.expiresAt,
        )
      } yield smithy.VerifyEmailResponse(
        accessTokenExpiresInSeconds = accessToken.expiresIn.toSeconds,
        onboardStage = onboardStageFromDomainToSmithy(OnboardStage.EmailVerified),
        refreshToken = refreshToken.jwt.value,
        accessToken = accessToken.jwt.value,
      )
  }

  private def observed(
      service: smithy.UserSignupService[ServiceTask]
  ): smithy.UserSignupService[Task] =
    new smithy.UserSignupService[Task] {

      /** HTTP POST /signup/email */
      override def signUpEmail(request: smithy.SignUpEmailRequest): Task[smithy.SignUpEmailResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpEmail(request))

      /** HTTP POST /verify/email */
      override def verifyEmail(request: smithy.VerifyEmailRequest): Task[smithy.VerifyEmailResponse] = HttpErrorHandler
        .errorResponseHandler(service.verifyEmail(request))
    }

  val live = ZLayer.derive[UserSignupServiceImpl] >>> ZLayer.fromFunction(observed)
}
