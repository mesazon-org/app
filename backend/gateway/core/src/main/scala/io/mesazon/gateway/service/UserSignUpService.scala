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
      signUpEmailPostRequestServiceValidator: SignUpEmailPostRequestServiceValidator,
      signUpVerifyEmailPostRequestServiceValidator: SignUpVerifyEmailPostRequestServiceValidator,
  ) extends smithy.UserSignUpService[ServiceTask] {

    /** HTTP POST /signup/email */
    override def signUpEmailPost(request: smithy.SignUpEmailPostRequest): ServiceTask[smithy.SignUpEmailPostResponse] =
      for {
        _                 <- ZIO.logDebug(s"Signing up user with email: $request")
        signUpEmail       <- signUpEmailPostRequestServiceValidator.validate(request)
        userDetailsRowOpt <- userDetailsRepository.getUserDetailsByEmail(signUpEmail.email)
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
                if (isEmptyOrExpiredOrExpiringSoon) otpGenerator.generate
                else userOtpRowOpt.map(_.otp).fold(otpGenerator.generate)(ZIO.succeed(_))
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
                .insertUserDetails(signUpEmail.email, OnboardStage.EmailVerification)
              expiresAtNew <- timeProvider.instantNow
                .map(_.plusSeconds(userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds))
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
                  Schedule.recurs(userSignUpConfig.sendEmailVerificationEmailMaxRetries) && Schedule
                    .exponential(userSignUpConfig.sendEmailVerificationEmailRetryDelay)
                )
            } yield userOtpRowNew.otpID
          case _ => idGenerator.generate.map(OtpID.assume)
        }
      } yield smithy.SignUpEmailPostResponse(
        otpID.value,
        userSignUpConfig.otpEmailVerificationExpiresAtOffset.toSeconds,
      )

    /** HTTP POST /signup/verify/email */
    override def signUpVerifyEmailPost(
        request: smithy.SignUpVerifyEmailPostRequest
    ): ServiceTask[smithy.SignUpVerifyEmailPostResponse] =
      for {
        _                  <- ZIO.logDebug(s"Verify email: [${request.otpID}]")
        signUpVerifyEmail  <- signUpVerifyEmailPostRequestServiceValidator.validate(request)
        userOtpRowOpt      <- userOtpRepository.getUserOtpByOtpID(signUpVerifyEmail.otpID, OtpType.EmailVerification)
        userOtpRowExisting <- ZIO.getOrFailWith(
          ServiceError.UnauthorizedError.OtpValidationError(
            s"No otp found for otpID: [${signUpVerifyEmail.otpID}] and otpType: [${OtpType.EmailVerification}]"
          )
        )(userOtpRowOpt)
        userDetailsRowOpt      <- userDetailsRepository.getUserDetails(userOtpRowExisting.userID)
        userDetailsRowExisting <- ZIO.getOrFailWith(
          ServiceError.InternalServerError.UnexpectedError(
            s"No user details found for userID: [${userOtpRowExisting.userID}] and otpID: [${userOtpRowExisting.otpID}]"
          )
        )(userDetailsRowOpt)
        _ <- verifyOnboardStage(
          onboardStageUser = userDetailsRowExisting.onboardStage,
          onboardStagesAllowed = OnboardStage.signUpVerifyEmailStages,
        )
        instantNow <- timeProvider.instantNow
        _          <- userOtpRowExisting.otpType match {
          case OtpType.EmailVerification
              if userOtpRowExisting.otp == signUpVerifyEmail.otp && userOtpRowExisting.expiresAt.value.isAfter(
                instantNow
              ) =>
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
              ServiceError.UnauthorizedError.OtpValidationError(
                s"Wrong or expired OTP provided for otpID: [${userOtpRowExisting.otpID}]"
              )
            )
        }
        _          <- userTokenRepository.deleteAllUserTokens(userDetailsRowExisting.userID)
        accessJwt  <- jwtService.generateAccessToken(userDetailsRowExisting.userID)
        refreshJwt <- jwtService.generateRefreshToken(userDetailsRowExisting.userID)
        _          <- userTokenRepository.upsertUserToken(
          refreshJwt.tokenID,
          userDetailsRowExisting.userID,
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
      override def signUpEmailPost(request: smithy.SignUpEmailPostRequest): Task[smithy.SignUpEmailPostResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpEmailPost(request))

      /** HTTP POST /signup/verify/email */
      override def signUpVerifyEmailPost(
          request: smithy.SignUpVerifyEmailPostRequest
      ): Task[smithy.SignUpVerifyEmailPostResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.signUpVerifyEmailPost(request))
    }

  val local = ZLayer.derive[UserSignUpServiceImpl].project[smithy.UserSignUpService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
