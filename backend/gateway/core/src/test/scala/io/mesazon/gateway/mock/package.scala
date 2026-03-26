package io.mesazon.gateway

import cats.data.{NonEmptyChain, ValidatedNec}
import cats.syntax.all.*
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import io.mesazon.domain.waha.WahaError
import io.mesazon.domain.waha.output.ChattingSendMessageOutput
import io.mesazon.gateway.auth.{AuthorizationService, AuthorizationState}
import io.mesazon.gateway.clients.OpenAIClient
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.{UserOnboardRow, WahaUserActivityRow, WahaUserMessageRow, WahaUserRow}
import io.mesazon.gateway.validation.EmailValidator.EmailRaw
import io.mesazon.gateway.validation.PhoneNumberValidator.PhoneNumberRegion
import io.mesazon.generator.IDGenerator
import io.mesazon.testkit.base.ZIOTestOps
import io.mesazon.waha.WahaClient
import org.http4s.Request
import sttp.ai.openai.requests.completions.chat.message
import sttp.tapir.Schema
import zio.*
import zio.stream.*

import java.time.{Clock, Instant}
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import validation.*

package object mock extends ZIOTestOps {

  def pingRepositoryMockLive(): ULayer[PingRepository] = ZLayer.succeed(
    new PingRepository {
      override def ping(): IO[ServiceError.ServiceUnavailableError.DatabaseUnavailableError, Unit] = ZIO.unit
    }
  )

  def userManagementRepositoryMockLive(
      userOnboardRows: Map[UserID, UserOnboardRow] = Map.empty,
      insertUserOnboardEmailRef: Ref[Int] = Ref.make(0).zioValue,
      updateUserOnboardRef: Ref[Int] = Ref.make(0).zioValue,
      getUserOnboardRef: Ref[Int] = Ref.make(0).zioValue,
      getUserOnboardEmailRef: Ref[Int] = Ref.make(0).zioValue,
      insertUserDetailsCounterRef: Ref[Int] = Ref.make(0).zioValue,
      updateUserDetailsCounterRef: Ref[Int] = Ref.make(0).zioValue,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ): ULayer[UserManagementRepository] =
    ZLayer.succeed(
      new UserManagementRepository {

        override def insertUserDetails(
            userID: UserID,
            email: Email,
            userDetails: OnboardUserDetails,
        ): IO[ServiceError.ConflictError.UserAlreadyExists, Unit] =
          insertUserDetailsCounterRef.incrementAndGet *> maybeServiceError
            .map(_.asInstanceOf[ServiceError.ConflictError.UserAlreadyExists])
            .fold(
              maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)
            )(ZIO.fail)

        override def updateUserDetails(userID: UserID, updateUserDetails: UpdateUserDetails): UIO[Unit] =
          updateUserDetailsCounterRef.incrementAndGet *> maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)

        override def insertUserOnboardEmail(
            email: Email,
            stage: OnboardStage,
        ): IO[ServiceError.ConflictError.UserAlreadyExists, UserOnboardRow] =
          insertUserOnboardEmailRef.incrementAndGet *> maybeServiceError
            .map(_.asInstanceOf[ServiceError.ConflictError.UserAlreadyExists])
            .fold(
              maybeUnexpectedError.fold(
                ZIO.succeed(userOnboardRows.values.filter(_.email == email).head)
              )(ZIO.fail(_).orDie)
            )(ZIO.fail)

        override def updateUserOnboard(
            userID: UserID,
            fullName: Option[FullName],
            phoneNumber: Option[PhoneNumberE164],
            passwordHash: Option[PasswordHash],
            stage: OnboardStage,
        ): UIO[Unit] = updateUserOnboardRef.incrementAndGet *> maybeUnexpectedError.fold(ZIO.unit)(ZIO.fail(_).orDie)

        override def getUserOnboard(
            userID: UserID
        ): IO[ServiceError.InternalServerError.UserNotFoundError, UserOnboardRow] =
          getUserOnboardRef.incrementAndGet *> maybeServiceError
            .map(_.asInstanceOf[ServiceError.InternalServerError.UserNotFoundError])
            .fold(
              maybeUnexpectedError.fold(
                ZIO.getOrFailWith(
                  ServiceError.InternalServerError.UserNotFoundError("not found")
                )(userOnboardRows.get(userID))
              )(ZIO.fail(_).orDie)
            )(ZIO.fail)

        override def getUserOnboardByEmail(
            email: Email
        ): IO[ServiceError.InternalServerError.UserNotFoundError, UserOnboardRow] =
          getUserOnboardEmailRef.incrementAndGet *> maybeServiceError
            .map(_.asInstanceOf[ServiceError.InternalServerError.UserNotFoundError])
            .fold(
              maybeUnexpectedError.fold(
                ZIO.getOrFailWith(
                  ServiceError.InternalServerError.UserNotFoundError("not found")
                )(userOnboardRows.values.find(_.email == email))
              )(ZIO.fail(_).orDie)
            )(ZIO.fail)
      }
    )

  def userContactsRepositoryMockLive(
      upsertUserContactsCounterRef: Ref[Int],
      maybeError: Option[Throwable] = None,
  ): ULayer[UserContactRepository] = ZLayer.succeed(
    new UserContactRepository {
      override def upsertUserContacts(userID: UserID, upsertUserContacts: NonEmptyChunk[UpsertUserContact]): UIO[Unit] =
        maybeError.fold(upsertUserContactsCounterRef.incrementAndGet.unit)(ZIO.fail(_).orDie)
    }
  )

  def authorizationStateMockLive(authedUser: AuthedUser): ULayer[AuthorizationState] =
    ZLayer.succeed(
      new AuthorizationState {
        override def get(): UIO[AuthedUser] = ZIO.succeed(authedUser)

        override def set(authedUser: AuthedUser): UIO[Unit] = ZIO.unit
      }
    )

  def authorizationServiceMockLive(maybeError: Option[Throwable] = None): ULayer[AuthorizationService[Throwable]] =
    ZLayer.succeed(
      new AuthorizationService[Throwable] {
        override def auth(request: Request[Task]): Task[Unit] =
          maybeError.fold(ZIO.unit)(ZIO.fail(_))
      }
    )

  def phoneNumberRegionValidatorMockLive(): ULayer[DomainValidator[PhoneNumberRegion, PhoneNumberE164]] =
    ZLayer.succeed(
      new DomainValidator[PhoneNumberRegion, PhoneNumberE164] {
        override def validate(rawData: PhoneNumberRegion): UIO[ValidatedNec[InvalidFieldError, PhoneNumberE164]] =
          ZIO.succeed(PhoneNumberE164.assume(rawData.phoneNationalNumber).validNec)
      }
    )

  def wahaPhoneNumberValidatorMockLive(): ULayer[DomainValidator[waha.WahaPhone, PhoneNumberE164]] =
    ZLayer.succeed(
      new DomainValidator[waha.WahaPhone, PhoneNumberE164] {
        override def validate(rawData: waha.WahaPhone): UIO[ValidatedNec[InvalidFieldError, PhoneNumberE164]] =
          ZIO.succeed(PhoneNumberE164.assume(s"+${rawData.value}").validNec)

      }
    )

  def emailValidatorMockLive(
      invalidFieldError: Option[InvalidFieldError] = None
  ): ULayer[ServiceValidator[EmailRaw, Email]] =
    ZLayer.succeed {
      val domainValidator: DomainValidator[EmailRaw, Email] = rawData =>
        invalidFieldError
          .fold[IO[NonEmptyChain[InvalidFieldError], Email]](ZIO.succeed(Email.assume(rawData)))(invalid =>
            ZIO.fail(NonEmptyChain(invalid))
          )
          .fold(_.invalid, _.validNec)

      toServiceValidator(domainValidator)
    }

  def idGeneratorMockLive: ULayer[IDGenerator] =
    ZLayer.succeed {
      val atomicInt = new AtomicInteger(0)

      new IDGenerator {
        override def generate: UIO[String] = ZIO.succeed(atomicInt.incrementAndGet().toString)
      }
    }

  def idGeneratorMockConstLive(id: String): ULayer[IDGenerator] =
    ZLayer.succeed {
      new IDGenerator {
        override def generate: UIO[String] = ZIO.succeed(id)
      }
    }

  def timeProviderMockLive(clock: Clock): ULayer[TimeProvider] =
    ZLayer.succeed(clock) >>> TimeProvider.live

  def wahaClientMockLive(
      chattingSendSeenCounterRef: Ref[Int] = Ref.make(0).zioValue,
      chattingSendMessageCounterRef: Ref[Int] = Ref.make(0).zioValue,
  ): ULayer[WahaClient] =
    ZLayer.succeed(
      new WahaClient {
        override def chattingSendSeen(input: waha.input.ChattingSeenInput): IO[WahaError, Unit] =
          chattingSendSeenCounterRef.incrementAndGet *> ZIO.unit

        override def chattingSendMessage(
            input: waha.input.ChattingMessageInput
        ): IO[WahaError, ChattingSendMessageOutput] =
          chattingSendMessageCounterRef.incrementAndGet.map(_ =>
            ChattingSendMessageOutput(waha.MessageID.assume(UUID.randomUUID().toString))
          )

        override def groupsCreate(
            input: waha.input.GroupsCreateInput
        ): IO[waha.WahaError, waha.output.GroupsCreateOutput] = ???

        override def groupsUpdate(
            input: waha.input.GroupsUpdateInput
        ): IO[waha.WahaError, waha.output.GroupsUpdateOutput] = ???

        override def groupsLeave(input: waha.input.GroupsLeaveInput): IO[waha.WahaError, Unit] = ???

        override def groupsInviteCode(
            input: waha.input.GroupsInviteCodeInput
        ): IO[waha.WahaError, waha.output.GroupsInviteCodeOutput] = ???

        override def groupsGetInfo(
            input: waha.input.GroupsGetInfoInput
        ): IO[waha.WahaError, waha.output.GroupsGetInfoOutput] = ???

      }
    )

  def wahaRepositoryMockLive(
      wahaUserRows: Map[UserID, WahaUserRow] = Map.empty,
      wahaUserActivityRows: Map[UserID, WahaUserActivityRow] = Map.empty,
      wahaUserMessageRows: Map[UserID, List[WahaUserMessageRow]] = Map.empty,
      createOrGetWahaUserCounterRef: Ref[Int] = Ref.make(0).zioValue,
      getWahaUserWithWahaUserIdCounterRef: Ref[Int] = Ref.make(0).zioValue,
      getWahaUserCounterRef: Ref[Int] = Ref.make(0).zioValue,
      upsertWahaUserActivityCounterRef: Ref[Int] = Ref.make(0).zioValue,
      getUserWahaUserActivityCounterRef: Ref[Int] = Ref.make(0).zioValue,
      insertWahaUserMessageCounterRef: Ref[Int] = Ref.make(0).zioValue,
      getWahaUserMessagesCounterRef: Ref[Int] = Ref.make(0).zioValue,
      getWahaUsersActivityWaitingForAssistantReplyCounterRef: Ref[Int] = Ref.make(0).zioValue,
  ): ULayer[WahaRepository] =
    ZLayer.succeed(
      new WahaRepository {
        override def createOrGetWahaUser(
            wahaUserID: waha.UserID,
            wahaFullName: waha.FullName,
            wahaUserAccountID: waha.UserAccountID,
            wahaChatID: waha.ChatID,
            phoneNumber: PhoneNumberE164,
        ): IO[DbException, WahaUserRow] =
          createOrGetWahaUserCounterRef.incrementAndGet *> ZIO.succeed(
            WahaUserRow(
              UserID.assume(UUID.randomUUID().toString),
              wahaFullName,
              wahaUserID,
              wahaUserAccountID,
              wahaChatID,
              phoneNumber,
              CreatedAt.assume(Instant.now()),
              UpdatedAt.assume(Instant.now()),
            )
          )

        override def getWahaUserWithWahaUserId(
            wahaUserID: waha.UserID
        ): IO[DbException, Option[WahaUserRow]] =
          getWahaUserWithWahaUserIdCounterRef.incrementAndGet *> ZIO.succeed(
            wahaUserRows.collectFirst { case (_, row) if row.wahaUserID == wahaUserID => row }
          )

        override def getWahaUser(userID: UserID): IO[DbException, Option[WahaUserRow]] =
          getWahaUserCounterRef.incrementAndGet *> ZIO.succeed(
            wahaUserRows.get(userID)
          )

        override def upsertWahaUserActivity(
            userID: UserID,
            lastMessageID: Option[waha.MessageID],
            isWaitingAssistantReply: Boolean,
            forceUpdate: Boolean,
        ): IO[DbException, Unit] = upsertWahaUserActivityCounterRef.incrementAndGet *> ZIO.unit

        override def getUserWahaUserActivity(userID: UserID): IO[DbException, Option[WahaUserActivityRow]] =
          getUserWahaUserActivityCounterRef.incrementAndGet *> ZIO.succeed(wahaUserActivityRows.get(userID))

        override def insertWahaUserMessage(
            userID: UserID,
            messageID: waha.MessageID,
            message: waha.MessageText,
            isAssistant: Boolean,
        ): IO[DbException, Unit] = insertWahaUserMessageCounterRef.incrementAndGet *> ZIO.unit

        override def getWahaUserMessages(userID: UserID): Stream[DbException, WahaUserMessageRow] =
          ZStream.fromZIO(getWahaUserMessagesCounterRef.incrementAndGet) *> ZStream.fromIterable(
            wahaUserMessageRows.get(userID).toList.flatten
          )

        override def getWahaUsersActivityWaitingForAssistantReply: Stream[DbException, WahaUserActivityRow] =
          ZStream.fromZIO(getWahaUsersActivityWaitingForAssistantReplyCounterRef.incrementAndGet) *> ZStream
            .fromIterable(wahaUserActivityRows.values.filter(_.isWaitingAssistantReply))
      }
    )

  def openAIClientMockLive(
      assistantResponse: AssistantResponse,
      sendMessageCounterRef: Ref[Int] = Ref.make(0).zioValue,
      messagesCounterRef: Ref[Int] = Ref.make(0).zioValue,
  ): ULayer[OpenAIClient] =
    ZLayer.succeed(
      new OpenAIClient {
        override def sendMessage[A](
            messages: Seq[message.Message]
        )(using Schema[A], JsonValueCodec[A]): IO[ServiceError, A] =
          messagesCounterRef.set(messages.size) *> sendMessageCounterRef.incrementAndGet *> ZIO.succeed(
            assistantResponse.asInstanceOf[A]
          )
      }
    )
}
