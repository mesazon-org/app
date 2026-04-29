package io.mesazon.gateway

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import io.mesazon.domain.waha.WahaError
import io.mesazon.domain.waha.output.ChattingSendMessageOutput
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.state.AuthState
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

object Mocks extends ZIOTestOps {

  def pingRepositoryLive(): ULayer[PingRepository] = ZLayer.succeed(
    new PingRepository {
      override def ping(): IO[ServiceError.ServiceUnavailableError.DatabaseUnavailableError, Unit] = ZIO.unit
    }
  )

  def authStateLive(authedUser: AuthedUser): ULayer[AuthState] =
    ZLayer.succeed(
      new AuthState {
        override def get(): UIO[AuthedUser] = ZIO.succeed(authedUser)

        override def set(authedUser: AuthedUser): UIO[Unit] = ZIO.unit
      }
    )

  def authorizationServiceLive(maybeError: Option[Throwable] = None): ULayer[AuthorizationService[Task]] =
    ZLayer.succeed(
      new AuthorizationService[Task] {
        override def auth(request: Request[Task]): Task[Unit] =
          maybeError.fold(ZIO.unit)(ZIO.fail(_))
      }
    )

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
}
