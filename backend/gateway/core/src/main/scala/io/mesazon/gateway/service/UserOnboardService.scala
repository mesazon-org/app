package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{EmailClient, TwilioClient}
import io.mesazon.gateway.config.UserOnboardConfig
import io.mesazon.gateway.repository.*
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
      userOnboardRequestValidator: UserOnboardRequestValidator,
  ) extends smithy.UserOnboardService[ServiceTask] {

    /** HTTP POST /onboard/password */
    override def onboardPasswordPost(
        onboardPasswordPostRequestSmithy: smithy.OnboardPasswordPostRequest
    ): ServiceTask[smithy.OnboardPasswordPostResponse] =
      for {
        authedUser                 <- authState.get
        onboardPasswordPostRequest <- userOnboardRequestValidator
          .validatedOnboardPasswordPostRequest(onboardPasswordPostRequestSmithy)
        userDetailsRow <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          userID = authedUser.userID,
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = List(OnboardStage.EmailVerified),
        )
        passwordHash          <- passwordService.hashPassword(onboardPasswordPostRequest.password)
        _                     <- userCredentialsRepository.insertUserCredentials(authedUser.userID, passwordHash)
        userDetailsRowUpdated <- userDetailsRepository
          .updateUserDetails(authedUser.userID, OnboardStage.PasswordProvided)
        _ <- emailClient
          .sendWelcomeEmail(userDetailsRowUpdated.email)
          .retry(
            Schedule.recurs(userOnboardConfig.sendWelcomeEmailMaxRetries) && Schedule
              .exponential(userOnboardConfig.sendWelcomeEmailRetryDelay)
          )
          .catchAllCause(cause =>
            ZIO.logWarning(
              s"Failed to send welcome email after onboarding completed for userID=${authedUser.userID}, email=${userDetailsRowUpdated.email}"
            ) *> ZIO.logDebugCause("Welcome email send failure cause", cause)
          )
      } yield smithy.OnboardPasswordPostResponse(onboardStageFromDomainToSmithy(OnboardStage.PasswordProvided))

    /** HTTP POST /onboard/details */
    override def onboardDetailsPost(
        onboardDetailsPostRequestSmithy: smithy.OnboardDetailsPostRequest
    ): ServiceTask[smithy.OnboardDetailsPostResponse] =
      for {
        authedUser                <- authState.get
        onboardDetailsPostRequest <- userOnboardRequestValidator
          .validatedOnboardDetailsPostRequest(onboardDetailsPostRequestSmithy)
        userDetailsRow <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          userID = userDetailsRow.userID,
          onboardStageUser = userDetailsRow.onboardStage,
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
              otp        <- otpGenerator.generateOtp
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
                  Some(onboardDetailsPostRequest.fullName),
                  Some(onboardDetailsPostRequest.phoneNumber),
                )
              _ <- ZIO.unlessDiscard(userOnboardConfig.isDev)(
                twilioClient
                  .sendOtpSms(onboardDetailsPostRequest.phoneNumber.phoneNumberE164, userOtpRow.otp)
                  .retry(
                    Schedule.recurs(userOnboardConfig.sendPhoneVerificationOtpMaxRetries) && Schedule
                      .exponential(userOnboardConfig.sendPhoneVerificationOtpRetryDelay)
                  )
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
        onboardVerifyPhoneNumberPostRequestSmithy: smithy.OnboardVerifyPhoneNumberPostRequest
    ): ServiceTask[smithy.OnboardVerifyPhoneNumberPostResponse] =
      for {
        authedUser                          <- authState.get
        onboardVerifyPhoneNumberPostRequest <- userOnboardRequestValidator
          .validatedOnboardVerifyPhoneNumberPostRequest(onboardVerifyPhoneNumberPostRequestSmithy)
        userDetailsRow <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          userID = userDetailsRow.userID,
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.onboardVerifyPhoneNumberStages,
        )
        userOtpRow <- userOtpRepository
          .getUserOtp(onboardVerifyPhoneNumberPostRequest.otpID, authedUser.userID, OtpType.PhoneVerification)
          .someOrFail(
            ServiceError.InternalServerError
              .UnexpectedError(s"No OTP found for otpID: [${onboardVerifyPhoneNumberPostRequest.otpID}]")
          )
        instantNow <- timeProvider.instantNow
        _          <-
          if (userOtpRow.expiresAt.value.isBefore(instantNow))
            userOtpRepository.deleteUserOtp(
              userOtpRow.otpID,
              userOtpRow.userID,
              userOtpRow.otpType,
            ) *> ZIO.fail(
              ServiceError.UnauthorizedError
                .OtpExpiredError(s"Expired OTP provided for otpID: [${onboardVerifyPhoneNumberPostRequest.otpID}]")
            )
          else if (
            userOtpRow.otp == onboardVerifyPhoneNumberPostRequest.otp || verifyOtpInDev(
              onboardVerifyPhoneNumberPostRequest.otp,
              userOnboardConfig.isDev,
            )
          )
            userDetailsRepository.updateUserDetails(
              authedUser.userID,
              OnboardStage.PhoneVerified,
            ) *> userOtpRepository.deleteUserOtp(userOtpRow.otpID, userOtpRow.userID, OtpType.PhoneVerification)
          else
            ZIO.fail(
              ServiceError.BadRequestError
                .OtpVerifyError(s"Wrong OTP provided for otpID: [${onboardVerifyPhoneNumberPostRequest.otpID}]")
            )
      } yield smithy.OnboardVerifyPhoneNumberPostResponse(
        onboardStage = onboardStageFromDomainToSmithy(OnboardStage.PhoneVerified)
      )

    /** HTTP GET /onboard/verify/phone-number */
    override def onboardVerifyPhoneNumberGet(): ServiceTask[smithy.OnboardVerifyPhoneNumberGetResponse] =
      for {
        authedUser     <- authState.get
        userDetailsRow <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"User details not found for userID: [${authedUser.userID}]"
            )
          )
        _ <- verifyOnboardStage(
          userID = userDetailsRow.userID,
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.onboardVerifyPhoneNumberStages,
        )
        userOtpRow <-
          userOtpRepository
            .getUserOtpByUserID(authedUser.userID, OtpType.PhoneVerification)
            .someOrFail(
              ServiceError.InternalServerError.UnexpectedError(
                s"No OTP found for userID: [${authedUser.userID}] and otpType: [${OtpType.PhoneVerification}]"
              )
            )
        instantNow <- timeProvider.instantNow
        _          <-
          if (
            userOtpRow.expiresAt.value
              .minusSeconds(userOnboardConfig.otpPhoneVerificationResendCooldown.toSeconds)
              .isBefore(instantNow)
          )
            userOtpRepository
              .deleteUserOtp(userOtpRow.otpID, userDetailsRow.userID, OtpType.PhoneVerification) *> ZIO.fail(
              ServiceError.UnauthorizedError.OtpExpiredError(s"OTP expired for otpID: [${userOtpRow.otpID}]")
            )
          else ZIO.unit
      } yield smithy.OnboardVerifyPhoneNumberGetResponse(
        otpID = userOtpRow.otpID.value,
        otpExpiresInSeconds = userOtpRow.expiresAt.value.getEpochSecond - instantNow.getEpochSecond,
      )
  }

  private def observed(userOnboardService: smithy.UserOnboardService[ServiceTask]): smithy.UserOnboardService[Task] =
    new smithy.UserOnboardService[Task] {
      override def onboardPasswordPost(
          onboardPasswordPostRequestSmithy: smithy.OnboardPasswordPostRequest
      ): Task[smithy.OnboardPasswordPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardPasswordPost(onboardPasswordPostRequestSmithy)
        )

      /** HTTP POST /onboard/details */
      override def onboardDetailsPost(
          onboardDetailsPostRequestSmithy: smithy.OnboardDetailsPostRequest
      ): Task[smithy.OnboardDetailsPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardDetailsPost(onboardDetailsPostRequestSmithy)
        )

      /** HTTP POST /onboard/verify/phone-number */
      override def onboardVerifyPhoneNumberPost(
          onboardVerifyPhoneNumberPostRequestSmithy: smithy.OnboardVerifyPhoneNumberPostRequest
      ): Task[smithy.OnboardVerifyPhoneNumberPostResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardVerifyPhoneNumberPost(onboardVerifyPhoneNumberPostRequestSmithy)
        )

      /** HTTP GET /onboard/verify/phone-number */
      override def onboardVerifyPhoneNumberGet(): Task[smithy.OnboardVerifyPhoneNumberGetResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardVerifyPhoneNumberGet()
        )
    }

  val local = ZLayer.derive[UserOnboardServiceImpl].project[smithy.UserOnboardService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
