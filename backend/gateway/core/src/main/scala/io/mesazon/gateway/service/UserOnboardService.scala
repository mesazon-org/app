package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.{AuthorizationState, PasswordService}
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.UserOnboardConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserOnboardService {

  private final class UserOnboardServiceImpl(
      userOnboardConfig: UserOnboardConfig,
      authorizationState: AuthorizationState,
      userCredentialsRepository: UserCredentialsRepository,
      userDetailsRepository: UserDetailsRepository,
      emailClient: EmailClient,
      passwordService: PasswordService,
      onboardPasswordValidator: ServiceValidator[smithy.OnboardPasswordRequest, OnboardPassword],
  ) extends smithy.UserOnboardService[ServiceTask] {

    /** HTTP POST /onboard/password */
    override def onboardPassword(request: smithy.OnboardPasswordRequest): ServiceTask[smithy.OnboardResponse] =
      for {
        authedUser  <- authorizationState.get()
        userDetails <- userDetailsRepository
          .getUserDetails(authedUser.userID)
          .someOrFail(
            ServiceError.InternalServerError.UserNotFoundError(
              s"User details not found for userID: ${authedUser.userID}"
            )
          )
        _ <- verifyOnboardStage(
          onboardStageUser = userDetails.onboardStage,
          onboardStagesAllowed = List(OnboardStage.EmailVerified),
        )
        onboardPassword <- onboardPasswordValidator.validate(request)
        passwordHash    <- passwordService.hashPassword(onboardPassword.password)
        _               <- userCredentialsRepository.insertUserCredentials(authedUser.userID, passwordHash)
        userDetailsRow  <- userDetailsRepository
          .updateUserDetails(authedUser.userID, OnboardStage.PasswordProvided)
        _ <- emailClient
          .sendWelcomeEmail(userDetailsRow.email)
          .retry(
            Schedule.recurs(userOnboardConfig.sendEmailVerificationEmailMaxRetries) && Schedule
              .exponential(userOnboardConfig.sendEmailVerificationEmailRetryDelay)
          )
      } yield smithy.OnboardResponse(onboardStageFromDomainToSmithy(OnboardStage.PasswordProvided))
  }

  private def observed(userOnboardService: smithy.UserOnboardService[ServiceTask]): smithy.UserOnboardService[Task] =
    new smithy.UserOnboardService[Task] {
      override def onboardPassword(request: smithy.OnboardPasswordRequest): Task[smithy.OnboardResponse] =
        HttpErrorHandler.errorResponseHandler(
          userOnboardService.onboardPassword(request)
        )
    }

  val live = ZLayer.derive[UserOnboardServiceImpl] >>> ZLayer.fromFunction(observed)
}
