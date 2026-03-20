package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.auth.AuthorizationState
import io.mesazon.gateway.repository.UserContactsRepository
import io.mesazon.gateway.validation.ServiceValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserContactsService {

  private final class UserContactsServiceImpl(
      authorizationState: AuthorizationState,
      userContactsRepository: UserContactsRepository,
      upsertUserContactsValidator: ServiceValidator[Set[smithy.UpsertUserContactRequest], NonEmptyChunk[
        UpsertUserContact
      ]],
  ) extends smithy.UserContactsService[[A] =>> IO[ServiceError, A]] {

    override def upsertContacts(request: Set[smithy.UpsertUserContactRequest]): IO[ServiceError, Unit] =
      for {
        _                  <- ZIO.logDebug(s"Upserting user contacts with request: $request")
        authedUser         <- authorizationState.get()
        upsertUserContacts <- upsertUserContactsValidator.validate(request)
        _                  <- userContactsRepository.upsertUserContacts(authedUser.userID, upsertUserContacts)
      } yield ()
  }

  private def observed(
      service: smithy.UserContactsService[[A] =>> IO[ServiceError, A]]
  ): smithy.UserContactsService[Task] =
    new smithy.UserContactsService[Task] {
      override def upsertContacts(request: Set[smithy.UpsertUserContactRequest]): Task[Unit] =
        HttpErrorHandler
          .errorResponseHandler(service.upsertContacts(request))
    }

  val live = ZLayer.derive[UserContactsServiceImpl] >>> ZLayer.fromFunction(observed)
}
