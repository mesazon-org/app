package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.mesazon.generator.IDGenerator
import zio.*

object UserForgotPasswordService {

  private final class UserForgotPasswordServiceImpl(
      userForgotPasswordConfig: UserForgotPasswordConfig,
      userDetailsRepository: UserDetailsRepository,
      userActionAttemptRepository: UserActionAttemptRepository,
      userOtpRepository: UserOtpRepository,
      userTokenRepository: UserTokenRepository,
      userCredentialsRepository: UserCredentialsRepository,
      passwordService: PasswordService,
      otpGenerator: OtpGenerator,
      idGenerator: IDGenerator,
      emailClient: EmailClient,
      jwtService: JwtService,
      timeProvider: TimeProvider,
      forgotPasswordPostRequestServiceValidator: ForgotPasswordPostRequestServiceValidator,
      forgotPasswordVerifyOTPPostRequestServiceValidator: ForgotPasswordVerifyOTPPostRequestServiceValidator,
      forgotPasswordResetPostRequestServiceValidator: ForgotPasswordResetPostRequestServiceValidator,
  ) extends smithy.UserForgotPasswordService[ServiceTask] {

    /** HTTP POST /forgot/password */
    override def forgotPasswordPost(
        request: smithy.ForgotPasswordPostRequest
    ): ServiceTask[smithy.ForgotPasswordPostResponse] = for {
      _              <- ZIO.logDebug(s"Received forgot password request: [$request]")
      forgotPassword <- forgotPasswordPostRequestServiceValidator.validate(request)
      userDetailsOpt <- userDetailsRepository.getUserDetailsByEmail(forgotPassword.email)
      otpID          <- userDetailsOpt match {
        case Some(userDetails) =>
          for {
            _             <- verifyOnboardStage(userDetails.onboardStage, OnboardStage.forgotPasswordAllowedStages)
            userOtpRowOpt <- userOtpRepository.getUserOtpByUserID(
              userDetails.userID,
              OtpType.ForgotPassword,
            )
            instantNow <- timeProvider.instantNow
            otpID      <- userOtpRowOpt match {
              case Some(userOtpRow)
                  if userOtpRow.expiresAt.value
                    .minusSeconds(userForgotPasswordConfig.otpResendCooldown.toSeconds)
                    .isAfter(instantNow) =>
                for {
                  userActionAttemptRow <- userActionAttemptRepository.getAndIncreaseUserActionAttempt(
                    userDetails.userID,
                    ActionAttemptType.ForgotPassword,
                  )
                  otpID <-
                    if (userActionAttemptRow.attempts.value > userForgotPasswordConfig.otpResetAttemptsMaxRetries) {
                      // Preventing email scanning by not revealing whether the OTP is valid or not and just returning the existing OTP ID without sending email
                      // Note: This also means that if the user keeps requesting OTPs, they will keep getting the same OTP until the resend cooldown expires,
                      // which is a common practice to prevent abuse while still allowing legitimate users to receive their OTPs.
                      ZIO.succeed(userOtpRow.otpID)
                    } else
                      userOtpRepository
                        .updateUserOtp(
                          otpID = userOtpRow.otpID,
                          userID = userDetails.userID,
                          otpType = OtpType.ForgotPassword,
                          expiresAtUpdate =
                            ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds)),
                        )
                        .map(_.otpID)
                } yield otpID
              case _ =>
                for {
                  otpNew <- otpGenerator.generateOtp
                  expiresAt = ExpiresAt(instantNow.plusSeconds(userForgotPasswordConfig.otpExpiresAtOffset.toSeconds))
                  userOtpRow <- userOtpRepository.upsertUserOtp(
                    userID = userDetails.userID,
                    otpType = OtpType.ForgotPassword,
                    otp = otpNew,
                    expiresAt = expiresAt,
                  )
                  _ <- userActionAttemptRepository.deleteUserActionAttempt(
                    userID = userDetails.userID,
                    actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
                  )
                  _ <- emailClient
                    .sendForgotPasswordEmail(
                      email = forgotPassword.email,
                      otp = userOtpRow.otp,
                    )
                    .retry(
                      Schedule.recurs(userForgotPasswordConfig.sendForgotPasswordEmailMaxRetries) && Schedule
                        .exponential(userForgotPasswordConfig.sendForgotPasswordEmailRetryDelay)
                    )
                } yield userOtpRow.otpID
            }
          } yield otpID
        case None =>
          idGenerator.generateID
            .map(OtpID.either)
            .flatMap(
              ZIO
                .fromEither(_)
                .mapError(e => ServiceError.InternalServerError.UnexpectedError(s"Failed to generate OTP ID: [$e]"))
            )
      }
    } yield smithy.ForgotPasswordPostResponse(
      otpID = otpID.value,
      otpExpiresInSeconds = userForgotPasswordConfig.otpExpiresAtOffset.toSeconds,
    )

    /** HTTP POST /forgot/password/verify-otp */
    override def forgotPasswordVerifyOTPPost(
        request: smithy.ForgotPasswordVerifyOTPPostRequest
    ): ServiceTask[smithy.ForgotPasswordVerifyOTPPostResponse] =
      for {
        _                       <- ZIO.logDebug(s"Received forgot password verify OTP request for otpID: [$request]")
        forgotPasswordVerifyOTP <- forgotPasswordVerifyOTPPostRequestServiceValidator.validate(request)
        userOtpRow              <- userOtpRepository
          .getUserOtpByOtpID(
            forgotPasswordVerifyOTP.otpID,
            OtpType.ForgotPassword,
          )
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"No OTP found for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
            )
          )
        userDetailsRow <- userDetailsRepository
          .getUserDetails(userOtpRow.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"No user details found for userID: [${userOtpRow.userID}] and otpID: [${userOtpRow.otpID}]"
            )
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
        )
        userActionAttemptsRow <- userActionAttemptRepository.getAndIncreaseUserActionAttempt(
          userID = userOtpRow.userID,
          actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
        )
        _ <-
          if (userActionAttemptsRow.attempts.value > userForgotPasswordConfig.otpVerifyAttemptsMaxRetries)
            ZIO.fail(
              ServiceError.BadRequestError.OtpVerifyError(
                s"OTP validation attempts exceeded for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
              )
            )
          else ZIO.unit
        instantNow <- timeProvider.instantNow
        _          <-
          if (userOtpRow.expiresAt.value.isBefore(instantNow))
            userOtpRepository.deleteUserOtp(
              otpID = userOtpRow.otpID,
              userID = userOtpRow.userID,
              otpType = userOtpRow.otpType,
            ) *> ZIO.fail(
              ServiceError.UnauthorizedError.OtpExpiredError(
                s"Expired OTP provided for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
              )
            )
          else if (
            userOtpRow.otp != forgotPasswordVerifyOTP.otp || verifyOTPinDev(
              userOtpRow.otp,
              isDev = userForgotPasswordConfig.isDev,
            )
          )
            ZIO.fail(
              ServiceError.BadRequestError.OtpVerifyError(
                s"Wrong OTP provided for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
              )
            )
          else
            userOtpRepository.deleteUserOtp(
              otpID = forgotPasswordVerifyOTP.otpID,
              userID = userOtpRow.userID,
              otpType = OtpType.ForgotPassword,
            ) *> userActionAttemptRepository.deleteUserActionAttempt(
              userID = userOtpRow.userID,
              actionAttemptType = ActionAttemptType.ForgotPassword,
            ) *> userActionAttemptRepository.deleteUserActionAttempt(
              userID = userOtpRow.userID,
              actionAttemptType = ActionAttemptType.ForgotPasswordVerifyOTP,
            )
        resetPasswordJwt <- jwtService.generateResetPasswordToken(userDetailsRow.userID)
        _                <- userTokenRepository
          .upsertUserToken(
            tokenID = resetPasswordJwt.tokenID,
            userID = userDetailsRow.userID,
            tokenType = TokenType.ResetPasswordToken,
            expiresAt = resetPasswordJwt.expiresAt,
          )
      } yield smithy.ForgotPasswordVerifyOTPPostResponse(
        resetPasswordToken = resetPasswordJwt.resetPasswordToken.value,
        resetPasswordTokenExpiresInSeconds = resetPasswordJwt.expiresIn.toSeconds,
      )

    /** HTTP POST /forgot/password/reset */
    override def forgotPasswordResetPost(request: smithy.ForgotPasswordResetPostRequest): ServiceTask[Unit] =
      for {
        _                       <- ZIO.logDebug("Received forgot password reset request")
        forgotPasswordReset     <- forgotPasswordResetPostRequestServiceValidator.validate(request)
        authedUserResetPassword <- jwtService.verifyResetPasswordToken(forgotPasswordReset.resetPasswordToken)
        userDetailsRow          <- userDetailsRepository
          .getUserDetails(authedUserResetPassword.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"No user details found for userID: [${authedUserResetPassword.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.forgotPasswordAllowedStages,
        )
        userTokenRow <- userTokenRepository
          .getUserToken(
            tokenID = authedUserResetPassword.tokenID,
            userID = authedUserResetPassword.userID,
            tokenType = TokenType.ResetPasswordToken,
          )
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"Reset password token not found for tokenID: [${authedUserResetPassword.tokenID}], userID: [${authedUserResetPassword.userID}] and tokenType: [${TokenType.ResetPasswordToken}]"
            )
          )
        passwordHash <- passwordService.hashPassword(forgotPasswordReset.password)
        _            <- userCredentialsRepository.updateUserCredentials(
          userID = authedUserResetPassword.userID,
          passwordHashUpdate = passwordHash,
        )
        _ <- userTokenRepository.deleteUserToken(
          tokenID = userTokenRow.tokenID,
          userID = userTokenRow.userID,
          tokenType = TokenType.ResetPasswordToken,
        )
        _ <- emailClient
          .sendPasswordChangeConfirmationEmail(
            email = userDetailsRow.email
          )
          .retry(
            Schedule.recurs(userForgotPasswordConfig.sendPasswordChangeConfirmationEmailMaxRetries) && Schedule
              .exponential(userForgotPasswordConfig.sendPasswordChangeConfirmationEmailRetryDelay)
          )
          .catchAllCause(cause =>
            ZIO.logErrorCause(
              s"Failed to send password change confirmation email for userID: [${authedUserResetPassword.userID}]",
              cause,
            )
          )
      } yield ()
  }

  private def observed(service: smithy.UserForgotPasswordService[ServiceTask]): smithy.UserForgotPasswordService[Task] =
    new smithy.UserForgotPasswordService[Task] {
      override def forgotPasswordPost(
          request: smithy.ForgotPasswordPostRequest
      ): Task[smithy.ForgotPasswordPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.forgotPasswordPost(request))

      override def forgotPasswordVerifyOTPPost(
          request: smithy.ForgotPasswordVerifyOTPPostRequest
      ): Task[smithy.ForgotPasswordVerifyOTPPostResponse] =
        HttpErrorHandler.errorResponseHandler(service.forgotPasswordVerifyOTPPost(request))

      override def forgotPasswordResetPost(request: smithy.ForgotPasswordResetPostRequest): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.forgotPasswordResetPost(request))
    }

  val local =
    ZLayer.derive[UserForgotPasswordServiceImpl].project[smithy.UserForgotPasswordService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
