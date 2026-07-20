package io.mesazon.gateway.service

import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.UserSignUpConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import io.mesazon.generator.IDGenerator
import zio.*

object UserSignUpService {

  private final class UserSignUpServiceImpl(
      userSignUpConfig: UserSignUpConfig,
      userOtpRepository: UserOtpRepository,
      userTokenRepository: UserTokenRepository,
      userDetailsRepository: UserDetailsRepository,
      jwtService: JwtService,
      timeProvider: TimeProvider,
      emailClient: EmailClient,
      otpGenerator: OtpGenerator,
      idGenerator: IDGenerator,
      userSignUpRequestValidator: UserSignUpRequestValidator,
  ) extends smithy.UserSignUpService[ServiceTask] {

    /** HTTP POST /signup/email */
    override def signUpEmailPost(
        signUpEmailPostRequestSmithy: smithy.SignUpEmailPostRequest
    ): ServiceTask[smithy.SignUpEmailPostResponse] =
      for {
        _                      <- ZIO.logDebug(s"Signing up user with email: $signUpEmailPostRequestSmithy")
        signUpEmailPostRequest <- userSignUpRequestValidator.validatedSignUpEmailPostRequest(
          signUpEmailPostRequestSmithy
        )
        userDetailsRowOpt <- userDetailsRepository.getUserDetailsByEmail(signUpEmailPostRequest.email)
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
              if OnboardStage.signUpEmailStages.contains(userDetailsRowExisting.onboardStage) =>
            for {
              instantNow <- timeProvider.instantNow
              isEmptyOrExpiredOrExpiringSoon = userOtpRowOpt.forall(
                _.expiresAt.value
                  .minusSeconds(userSignUpConfig.otpEmailVerificationResendCooldown.toSeconds)
                  .isBefore(instantNow)
              )
              userDetailsRowUpdated <- userDetailsRepository.updateUserDetails(
                userDetailsRowExisting.userID,
                OnboardStage.EmailVerification,
              )
              otpNew <-
                if (isEmptyOrExpiredOrExpiringSoon) otpGenerator.generateOtp
                else userOtpRowOpt.map(_.otp).fold(otpGenerator.generateOtp)(ZIO.succeed(_))
              expiresAtNew <-
                timeProvider.instantNow
                  .map(_.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds))
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
                    Schedule.recurs(userSignUpConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                      .exponential(userSignUpConfig.sendEmailVerificationEmailRetryDelay)
                  )
              )
            } yield userOtpRowNew.otpID
          case None =>
            for {
              userDetailsRowNew <- userDetailsRepository
                .insertUserDetails(signUpEmailPostRequest.email, OnboardStage.EmailVerification)
              expiresAtNew <- timeProvider.instantNow
                .map(_.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds))
                .map(ExpiresAt.assume)
              otpNew        <- otpGenerator.generateOtp
              userOtpRowNew <- userOtpRepository.upsertUserOtp(
                userDetailsRowNew.userID,
                OtpType.EmailVerification,
                otpNew,
                expiresAtNew,
              )
              _ <- emailClient
                .sendEmailVerificationEmail(userDetailsRowNew.email, otpNew)
                .retry(
                  Schedule.recurs(userSignUpConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                    .exponential(userSignUpConfig.sendEmailVerificationEmailRetryDelay)
                )
            } yield userOtpRowNew.otpID
          case _ => idGenerator.generateID.map(OtpID.assume)
        }
      } yield smithy.SignUpEmailPostResponse(
        otpID.value,
        userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
      )

    /** HTTP POST /signup/verify/email */
    override def signUpVerifyEmailPost(
        signUpVerifyEmailPostRequestSmithy: smithy.SignUpVerifyEmailPostRequest
    ): ServiceTask[smithy.SignUpVerifyEmailPostResponse] =
      for {
        _                            <- ZIO.logDebug(s"Verify email: [${signUpVerifyEmailPostRequestSmithy.otpID}]")
        signUpVerifyEmailPostRequest <- userSignUpRequestValidator.validatedSignUpVerifyEmailPostRequest(
          signUpVerifyEmailPostRequestSmithy
        )
        userOtpRow <- userOtpRepository
          .getUserOtpByOtpID(signUpVerifyEmailPostRequest.otpID, OtpType.EmailVerification)
          .someOrFail(
            ServiceError.InternalServerError.UnexpectedError(
              s"No otp found for otpID: [${signUpVerifyEmailPostRequest.otpID}] and otpType: [${OtpType.EmailVerification}]"
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
          userID = userDetailsRow.userID,
          onboardStageUser = userDetailsRow.onboardStage,
          onboardStagesAllowed = OnboardStage.signUpVerifyEmailStages,
        )
        instantNow <- timeProvider.instantNow
        _          <-
          if (userOtpRow.expiresAt.value.isBefore(instantNow))
            userOtpRepository
              .deleteUserOtp(
                userOtpRow.otpID,
                userOtpRow.userID,
                userOtpRow.otpType,
              ) *> ZIO.fail(
              ServiceError.UnauthorizedError.OtpExpiredError(
                s"Expired OTP provided for otpID: [${userOtpRow.otpID}]"
              )
            )
          else if (
            userOtpRow.otp == signUpVerifyEmailPostRequest.otp || verifyOtpInDev(
              signUpVerifyEmailPostRequest.otp,
              userSignUpConfig.isDev,
            )
          )
            userDetailsRepository
              .updateUserDetails(
                userDetailsRow.userID,
                OnboardStage.EmailVerified,
              ) *> userOtpRepository.deleteUserOtp(
              userOtpRow.otpID,
              userDetailsRow.userID,
              userOtpRow.otpType,
            )
          else
            ZIO.fail(
              ServiceError.BadRequestError.OtpVerifyError(
                s"Wrong OTP provided for otpID: [${userOtpRow.otpID}]"
              )
            )
        _          <- userTokenRepository.deleteAllUserTokens(userDetailsRow.userID)
        accessJwt  <- jwtService.generateAccessToken(userDetailsRow.userID)
        refreshJwt <- jwtService.generateRefreshToken(userDetailsRow.userID)
        _          <- userTokenRepository.upsertUserToken(
          refreshJwt.tokenID,
          userDetailsRow.userID,
          TokenType.RefreshToken,
          refreshJwt.expiresAt,
        )
      } yield smithy.SignUpVerifyEmailPostResponse(
        accessTokenExpiresInSeconds = accessJwt.expiresIn.toSeconds,
        onboardStage = onboardStageFromDomainToSmithy(OnboardStage.EmailVerified),
        refreshToken = refreshJwt.refreshToken.value,
        accessToken = accessJwt.accessToken.value,
      )
  }

  private def observed(
      service: smithy.UserSignUpService[ServiceTask]
  ): smithy.UserSignUpService[Task] =
    new smithy.UserSignUpService[Task] {

      /** HTTP POST /signup/email */
      override def signUpEmailPost(
          signUpEmailPostRequestSmithy: smithy.SignUpEmailPostRequest
      ): Task[smithy.SignUpEmailPostResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpEmailPost(signUpEmailPostRequestSmithy))

      /** HTTP POST /signup/verify/email */
      override def signUpVerifyEmailPost(
          signUpVerifyEmailPostRequestSmithy: smithy.SignUpVerifyEmailPostRequest
      ): Task[smithy.SignUpVerifyEmailPostResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpVerifyEmailPost(signUpVerifyEmailPostRequestSmithy))
    }

  val local = ZLayer.derive[UserSignUpServiceImpl].project[smithy.UserSignUpService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
