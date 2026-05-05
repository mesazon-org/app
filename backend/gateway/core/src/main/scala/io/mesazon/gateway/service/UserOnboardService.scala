package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{EmailClient, TwilioClient}
import io.mesazon.gateway.config.UserOnboardConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.smithy.OnboardVerifyPhoneNumberGetResponse
import io.mesazon.gateway.state.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserOnboardService {

  private final class UserOnboardServiceImpl(
      userOnboardConfig: UserOnboardConfig,
      authState: AuthState,
      userCredentialsRepository: UserCredentialsRepository,
      userDetailsRepository: UserDetailsRepository,
      userOtpRepository: UserOtpRepository,
      emailClient: EmailClient,
      twilioClient: TwilioClient,
      timeProvider: TimeProvider,
      otpGenerator: OtpGenerator,
      passwordService: PasswordService,
      onboardPasswordPostRequestServiceValidator: OnboardPasswordPostRequestServiceValidator,
      onboardDetailsPostRequestServiceValidator: OnboardDetailsPostRequestServiceValidator,
      onboardVerifyPhoneNumberPostRequestServiceValidator: OnboardVerifyPhoneNumberPostRequestServiceValidator,
  ) extends smithy.UserOnboardService[ServiceTask] {

    /** HTTP POST /onboard/password */
    override def onboardPasswordPost(
        request: smithy.OnboardPasswordPostRequest
    ): ServiceTask[smithy.OnboardPasswordPostResponse] =
      for {
        authedUser      <- authState.get()
        onboardPassword <- onboardPasswordPostRequestServiceValidator.validate(request)
        userDetails     <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UserNotFoundError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetails.onboardStage,
          onboardStagesAllowed = List(OnboardStage.EmailVerified),
        )
        passwordHash   <- passwordService.hashPassword(onboardPassword.password)
        _              <- userCredentialsRepository.insertUserCredentials(authedUser.userID, passwordHash)
        userDetailsRow <- userDetailsRepository
          .updateUserDetails(authedUser.userID, OnboardStage.PasswordProvided)
        _ <- emailClient
          .sendWelcomeEmail(userDetailsRow.email)
          .retry(
            Schedule.recurs(userOnboardConfig.sendWelcomeEmailMaxRetries) && Schedule
              .exponential(userOnboardConfig.sendWelcomeEmailRetryDelay)
          )
          .catchAllCause(cause =>
            ZIO.logWarning(
              s"Failed to send welcome email after onboarding completed for userID=${authedUser.userID}, email=${userDetailsRow.email}"
            ) *> ZIO.logDebugCause("Welcome email send failure cause", cause)
          )
      } yield smithy.OnboardPasswordPostResponse(onboardStageFromDomainToSmithy(OnboardStage.PasswordProvided))

    /** HTTP POST /onboard/details */
    override def onboardDetailsPost(
        request: smithy.OnboardDetailsPostRequest
    ): ServiceTask[smithy.OnboardDetailsPostResponse] =
      for {
        authedUser     <- authState.get()
        onboardDetails <- onboardDetailsPostRequestServiceValidator.validate(request)
        userDetails    <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UserNotFoundError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetails.onboardStage,
          onboardStagesAllowed = OnboardStage.onboardDetailsStages,
        )
        instantNow    <- timeProvider.instantNow
        userOtpRowOpt <- userOtpRepository.getUserOtpByUserID(authedUser.userID, OtpType.PhoneVerification)
        (userOtpRowNew, otpExpiresInSeconds) <- userOtpRowOpt match {
          case Some(userOtpRow)
              if userOtpRow.expiresAt.value
                .minusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds)
                .isAfter(instantNow) =>
            val otpExpiresInSeconds = userOtpRow.expiresAt.value.getEpochSecond - instantNow.getEpochSecond
            ZIO.succeed((userOtpRow, otpExpiresInSeconds))
          case _ =>
            for {
              otp        <- otpGenerator.generate
              userOtpRow <- userOtpRepository.upsertUserOtp(
                authedUser.userID,
                OtpType.PhoneVerification,
                otp,
                ExpiresAt(instantNow.plusSeconds(userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds)),
              )
              _ <- userDetailsRepository
                .updateUserDetails(
                  authedUser.userID,
                  OnboardStage.PhoneVerification,
                  Some(onboardDetails.fullName),
                  Some(onboardDetails.phoneNumber),
                )
              _ <- twilioClient
                .sendOtpSms(onboardDetails.phoneNumber.phoneNumberE164, userOtpRow.otp)
                .retry(
                  Schedule.recurs(userOnboardConfig.sendPhoneVerificationOtpMaxRetries) && Schedule
                    .exponential(userOnboardConfig.sendPhoneVerificationOtpRetryDelay)
                )
            } yield (userOtpRow, userOnboardConfig.otpPhoneVerificationExpiresAtOffset.toSeconds)
        }
      } yield smithy.OnboardDetailsPostResponse(
        onboardStage = onboardStageFromDomainToSmithy(OnboardStage.PhoneVerification),
        otpID = userOtpRowNew.otpID.value,
        otpExpiresInSeconds = otpExpiresInSeconds,
      )

    /** HTTP POST /onboard/verify/phone-number */
    override def onboardVerifyPhoneNumberPost(
        request: smithy.OnboardVerifyPhoneNumberPostRequest
    ): ServiceTask[smithy.OnboardVerifyPhoneNumberPostResponse] =
      for {
        authedUser               <- authState.get()
        onboardVerifyPhoneNumber <- onboardVerifyPhoneNumberPostRequestServiceValidator.validate(request)
        userDetails              <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UserNotFoundError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetails.onboardStage,
          onboardStagesAllowed = OnboardStage.onboardVerifyPhoneNumberStages,
        )
        userOtpRow <- userOtpRepository
          .getUserOtp(onboardVerifyPhoneNumber.otpID, authedUser.userID, OtpType.PhoneVerification)
          .someOrFail(
            ServiceError.UnauthorizedError
              .OtpValidationError(s"No OTP found for otpID: [${onboardVerifyPhoneNumber.otpID}]")
          )
        instantNow <- timeProvider.instantNow
        _          <-
          if (userOtpRow.otp == onboardVerifyPhoneNumber.otp && userOtpRow.expiresAt.value.isAfter(instantNow))
            for {
              _ <- userDetailsRepository.updateUserDetails(
                authedUser.userID,
                OnboardStage.PhoneVerified,
              )
              _ <- userOtpRepository.deleteUserOtp(userOtpRow.otpID, userOtpRow.userID, userOtpRow.otpType)
            } yield ()
          else
            ZIO.fail(
              ServiceError.UnauthorizedError
                .OtpValidationError(s"Wrong or expired OTP provided for otpID: [${onboardVerifyPhoneNumber.otpID}]")
            )
      } yield smithy.OnboardVerifyPhoneNumberPostResponse(
        onboardStage = onboardStageFromDomainToSmithy(OnboardStage.PhoneVerified)
      )

    /** HTTP GET /onboard/verify/phone-number */
    override def onboardVerifyPhoneNumberGet(): ServiceTask[OnboardVerifyPhoneNumberGetResponse] = ???
  }

  private def observed(userOnboardService: smithy.UserOnboardService[ServiceTask]): smithy.UserOnboardService[Task] =
    new smithy.UserOnboardService[Task] {
      override def onboardPasswordPost(
          request: smithy.OnboardPasswordPostRequest
      ): Task[smithy.OnboardPasswordPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardPasswordPost(request)
        )

      /** HTTP POST /onboard/details */
      override def onboardDetailsPost(
          request: smithy.OnboardDetailsPostRequest
      ): Task[smithy.OnboardDetailsPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardDetailsPost(request)
        )

      /** HTTP POST /onboard/verify/phone-number */
      override def onboardVerifyPhoneNumberPost(
          request: smithy.OnboardVerifyPhoneNumberPostRequest
      ): Task[smithy.OnboardVerifyPhoneNumberPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardVerifyPhoneNumberPost(request)
        )

      /** HTTP GET /onboard/verify/phone-number */
      override def onboardVerifyPhoneNumberGet(): Task[OnboardVerifyPhoneNumberGetResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardVerifyPhoneNumberGet()
        )
    }

  val local = ZLayer.derive[UserOnboardServiceImpl].project[smithy.UserOnboardService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
