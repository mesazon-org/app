package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.AuthorizationState
import io.mesazon.gateway.repository.UserContactRepository
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserContactsService {

  private final class UserContactsServiceImpl(
      authorizationState: AuthorizationState,
      userContactRepository: UserContactRepository,
      upsertUserContactsValidator: ServiceValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[
        UpsertUserContact
      ]],
  ) extends smithy.UserContactsService[ServiceTask] {

    override def upsertContacts(request: Set[smithy.UpsertUserContactRequest]): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Upserting user contacts with request: $request")
        authedUser         <- authorizationState.get()
        upsertUserContacts <- upsertUserContactsValidator.validate(request)
        _                  <- userContactRepository.upsertUserContacts(authedUser.userID, upsertUserContacts)
      } yield ()
  }

  private def observed(
      service: smithy.UserContactsService[ServiceTask]
  ): smithy.UserContactsService[Task] =
    new smithy.UserContactsService[Task] {
      override def upsertContacts(request: Set[smithy.UpsertUserContactRequest]): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.upsertContacts(request))
    }

  val live = ZLayer.derive[UserContactsServiceImpl] >>> ZLayer.fromFunction(observed)
}
