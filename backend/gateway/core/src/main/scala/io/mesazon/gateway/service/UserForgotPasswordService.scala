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
      userCredentialsRepository: UserCredentialsRepository,
      passwordService: PasswordService,
      otpGenerator: OtpGenerator,
      idGenerator: IDGenerator,
      emailClient: EmailClient,
      jwtService: JwtService,
      timeProvider: TimeProvider,
      forgotPasswordPostRequestServiceValidator: ForgotPasswordPostRequestServiceValidator,
  ) extends smithy.UserForgotPasswordService[ServiceTask] {

    /** HTTP POST /forgot/password */
    override def forgotPasswordPost(
        request: smithy.ForgotPasswordPostRequest
    ): ServiceTask[smithy.ForgotPasswordPostResponse] = for {
      _              <- ZIO.logDebug(s"Received forgot password request: $request")
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
      userCredentialsRepository.getUserCredentials(UserID.assume("")) *> passwordService.hashPassword(
        Password.assume("")
      ) *> jwtService.verifyAccessToken(AccessToken.assume("")) *> ZIO.succeed(???)

    /** HTTP POST /forgot/password/reset */
    override def forgotPasswordResetPost(request: smithy.ResetPasswordPostRequest): ServiceTask[Unit] = ???
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
