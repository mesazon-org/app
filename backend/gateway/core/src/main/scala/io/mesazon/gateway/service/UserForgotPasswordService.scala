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
                  otpNew <- otpGenerator.generate
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
          idGenerator.generate
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
            ServiceError.UnauthorizedError.OtpValidationError(
              s"No OTP found for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
            )
          )
        userDetailsRow <- userDetailsRepository
          .getUserDetails(userOtpRow.userID)
          .someOrFail(
            ServiceError.InternalServerError.UserNotFoundError(
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
              ServiceError.UnauthorizedError.OtpValidationError(
                s"OTP validation attempts exceeded for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
              )
            )
          else ZIO.unit
        instantNow <- timeProvider.instantNow
        _          <-
          if (userOtpRow.otp == forgotPasswordVerifyOTP.otp && userOtpRow.expiresAt.value.isAfter(instantNow))
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
          else
            ZIO.fail(
              ServiceError.UnauthorizedError.OtpValidationError(
                s"Wrong or expired OTP provided for OTP ID [${forgotPasswordVerifyOTP.otpID}] and OTP type [${OtpType.ForgotPassword}]"
              )
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
    override def forgotPasswordResetPost(request: smithy.ResetPasswordPostRequest): ServiceTask[Unit] =
      userCredentialsRepository.getUserCredentials(UserID.assume("")) *> passwordService.hashPassword(
        Password.assume(" ")
      ) *> ZIO.unit
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

      override def forgotPasswordResetPost(request: smithy.ResetPasswordPostRequest): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.forgotPasswordResetPost(request))
    }

  val local =
    ZLayer.derive[UserForgotPasswordServiceImpl].project[smithy.UserForgotPasswordService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
