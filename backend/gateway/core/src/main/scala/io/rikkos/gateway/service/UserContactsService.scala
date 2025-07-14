package io.rikkos.gateway.service

import io.rikkos.domain.*
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.gateway.repository.UserContactsRepository
import io.rikkos.gateway.validation.ServiceValidator
import io.rikkos.gateway.{smithy, HttpErrorHandler}
import zio.*

object UserContactsService {

  final case class UserContactsServiceImpl(
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

  val live = ZLayer.fromFunction(UserContactsServiceImpl.apply) >>> ZLayer.fromFunction(observed)
}
