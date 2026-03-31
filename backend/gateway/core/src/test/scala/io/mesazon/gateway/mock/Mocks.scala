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
import io.mesazon.gateway.auth.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
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

object Mocks extends ZIOTestOps {

  def pingRepositoryLive(): ULayer[PingRepository] = ZLayer.succeed(
    new PingRepository {
      override def ping(): IO[ServiceError.ServiceUnavailableError.DatabaseUnavailableError, Unit] = ZIO.unit
    }
  )

  def userManagementRepositoryLive(
      userOnboardRows: Map[UserID, UserOnboardRow] = Map.empty,
      userOtpRows: Map[OtpID, UserOtpRow] = Map.empty,
      insertUserOnboardEmailRef: Ref[Int] = Ref.make(0).zioValue,
      updateUserOnboardRef: Ref[Int] = Ref.make(0).zioValue,
      getUserOnboardRef: Ref[Int] = Ref.make(0).zioValue,
      getUserOnboardByEmailRef: Ref[Int] = Ref.make(0).zioValue,
      upsertUserOtpRef: Ref[Int] = Ref.make(0).zioValue,
      getUserOtpRef: Ref[Int] = Ref.make(0).zioValue,
      getUserOtpByUserIDRef: Ref[Int] = Ref.make(0).zioValue,
      insertUserDetailsCounterRef: Ref[Int] = Ref.make(0).zioValue,
      updateUserDetailsCounterRef: Ref[Int] = Ref.make(0).zioValue,
      maybeServiceError: Option[ServiceError] = None,
      maybeUnexpectedError: Option[Throwable] = None,
  ): ULayer[UserManagementRepository] =
    ZLayer.succeed(
      new UserManagementRepository {

        override def insertUserOnboardEmail(
            email: Email,
            stage: OnboardStage,
        ): IO[ServiceError, UserOnboardRow] =
          insertUserOnboardEmailRef.incrementAndGet *> maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(
                UserOnboardRow(
                  UserID.assume(UUID.randomUUID().toString),
                  email,
                  None,
                  None,
                  None,
                  stage,
                  CreatedAt.assume(Instant.now()),
                  UpdatedAt.assume(Instant.now()),
                )
              )
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

        override def updateUserOnboard(
            userID: UserID,
            stage: OnboardStage,
            fullName: Option[FullName],
            phoneNumber: Option[PhoneNumberE164],
            passwordHash: Option[PasswordHash],
        ): IO[ServiceError, UserOnboardRow] = updateUserOnboardRef.incrementAndGet *> maybeServiceError.fold(
          maybeUnexpectedError.fold(
            ZIO.succeed(userOnboardRows(userID))
          )(ZIO.fail(_).orDie)
        )(ZIO.fail)

        override def getUserOnboard(userID: UserID): IO[ServiceError, Option[UserOnboardRow]] =
          getUserOnboardRef.incrementAndGet *> maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(userOnboardRows.get(userID))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

        override def getUserOnboardByEmail(email: Email): IO[ServiceError, Option[UserOnboardRow]] =
          getUserOnboardByEmailRef.incrementAndGet *> maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(userOnboardRows.values.find(_.email == email))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

        override def upsertUserOtp(
            userID: UserID,
            otp: Otp,
            otpType: OtpType,
            expiresAt: ExpiresAt,
        ): IO[ServiceError, UserOtpRow] =
          upsertUserOtpRef.incrementAndGet *> maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(
                UserOtpRow(
                  OtpID.assume(UUID.randomUUID().toString),
                  userID,
                  otp,
                  otpType,
                  CreatedAt.assume(Instant.now()),
                  UpdatedAt.assume(Instant.now()),
                  expiresAt,
                )
              )
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

        override def getUserOtp(otpID: OtpID): IO[ServiceError, Option[UserOtpRow]] =
          getUserOtpRef.incrementAndGet *> maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(userOtpRows.get(otpID))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

        override def getUserOtpByUserID(userID: UserID, otpType: OtpType): IO[ServiceError, Option[UserOtpRow]] =
          getUserOtpByUserIDRef.incrementAndGet *> maybeServiceError.fold(
            maybeUnexpectedError.fold(
              ZIO.succeed(userOtpRows.values.find(row => row.userID == userID && row.otpType == otpType))
            )(ZIO.fail(_).orDie)
          )(ZIO.fail)

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
      }
    )

  def userContactsRepositoryLive(
      upsertUserContactsCounterRef: Ref[Int],
      maybeError: Option[Throwable] = None,
  ): ULayer[UserContactRepository] = ZLayer.succeed(
    new UserContactRepository {
      override def upsertUserContacts(userID: UserID, upsertUserContacts: NonEmptyChunk[UpsertUserContact]): UIO[Unit] =
        maybeError.fold(upsertUserContactsCounterRef.incrementAndGet.unit)(ZIO.fail(_).orDie)
    }
  )

  def authorizationStateLive(authedUser: AuthedUser): ULayer[AuthorizationState] =
    ZLayer.succeed(
      new AuthorizationState {
        override def get(): UIO[AuthedUser] = ZIO.succeed(authedUser)

        override def set(authedUser: AuthedUser): UIO[Unit] = ZIO.unit
      }
    )

  def authorizationServiceLive(maybeError: Option[Throwable] = None): ULayer[AuthorizationService[Throwable]] =
    ZLayer.succeed(
      new AuthorizationService[Throwable] {
        override def auth(request: Request[Task]): Task[Unit] =
          maybeError.fold(ZIO.unit)(ZIO.fail(_))
      }
    )

  def phoneNumberRegionValidatorLive(): ULayer[DomainValidator[PhoneNumberRegion, PhoneNumberE164]] =
    ZLayer.succeed(
      new DomainValidator[PhoneNumberRegion, PhoneNumberE164] {
        override def validate(rawData: PhoneNumberRegion): UIO[ValidatedNec[InvalidFieldError, PhoneNumberE164]] =
          ZIO.succeed(PhoneNumberE164.assume(rawData.phoneNationalNumber).validNec)
      }
    )

  def wahaPhoneNumberValidatorLive(): ULayer[DomainValidator[waha.WahaPhone, PhoneNumberE164]] =
    ZLayer.succeed(
      new DomainValidator[waha.WahaPhone, PhoneNumberE164] {
        override def validate(rawData: waha.WahaPhone): UIO[ValidatedNec[InvalidFieldError, PhoneNumberE164]] =
          ZIO.succeed(PhoneNumberE164.assume(s"+${rawData.value}").validNec)

      }
    )

  def emailValidatorLive(
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

  def idGeneratorLive: ULayer[IDGenerator] =
    ZLayer.succeed {
      val atomicInt = new AtomicInteger(0)

      new IDGenerator {
        override def generate: UIO[String] = ZIO.succeed(atomicInt.incrementAndGet().toString)
      }
    }

  def idGeneratorConstLive(id: String): ULayer[IDGenerator] =
    ZLayer.succeed {
      new IDGenerator {
        override def generate: UIO[String] = ZIO.succeed(id)
      }
    }

  def timeProviderLive(clock: Clock): ULayer[TimeProvider] =
    ZLayer.succeed(clock) >>> TimeProvider.live

  def wahaClientLive(
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

  def wahaRepositoryLive(
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

  def openAIClientLive(
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

  def emailClientLive(
      maybeError: Option[Throwable] = None,
      maybeServiceError: Option[ServiceError] = None,
      sendEmailVerificationEmailCounterRef: Ref[Int] = Ref.make(0).zioValue,
      sendWelcomeEmailCounterRef: Ref[Int] = Ref.make(0).zioValue,
  ): ULayer[EmailClient] =
    ZLayer.succeed(
      new EmailClient {
        override def sendEmailVerificationEmail(
            email: Email,
            otp: Otp,
        ): IO[ServiceError, Unit] =
          sendEmailVerificationEmailCounterRef.incrementAndGet *>
            maybeServiceError.fold(maybeError.fold(ZIO.unit)(ZIO.fail(_).orDie))(
              ZIO.fail
            )

        override def sendWelcomeEmail(email: Email, fullName: FullName): IO[ServiceError, Unit] =
          sendWelcomeEmailCounterRef.incrementAndGet *> maybeError.fold(ZIO.unit)(ZIO.fail(_).orDie)
      }
    )
}
