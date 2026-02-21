package io.mesazon.waha

import cats.data.NonEmptyList
import cats.syntax.all.*
import io.mesazon.waha.GroupsRequests.GroupNewParticipant
import io.mesazon.waha.config.WahaConfig
import io.rikkos.domain.waha.*
import io.rikkos.domain.waha.input.*
import io.rikkos.domain.waha.output.*
import io.scalaland.chimney.dsl.*
import sttp.client4.*
import zio.*
import zio.interop.catz.*

trait WahaClient {
  // Chatting API
  def chattingSendMessage(input: ChattingMessageInput): IO[WahaError, Unit]

  // Groups API
  def groupsCreate(input: GroupsCreateInput): IO[WahaError, GroupsCreateOutput]
  def groupsUpdate(input: GroupsUpdateInput): IO[WahaError, GroupsUpdateOutput]
  def groupsLeave(input: GroupsLeaveInput): IO[WahaError, Unit]
  def groupsInviteCode(input: GroupsInviteCodeInput): IO[WahaError, GroupsInviteCodeOutput]
  def groupsGetInfo(input: GroupsGetInfoInput): IO[WahaError, GroupsGetInfoOutput]
}

object WahaClient {

  type UserAccountsCheckResult = (registered: List[UserAccountID], nonRegistered: List[UserAccountID])

  private final class WahaClientImpl(config: WahaConfig)(using Backend[Task]) extends WahaClient {
    inline private val charactersPerWord = 5.0

    private val apiKeyHeader = getApiKeyHeader(config.apiKey)

    private def simulateHumanDelay: UIO[Unit] = Random
      .nextLongBetween(config.humanDelayMin.toMillis, config.humanDelayMax.toMillis)
      .flatMap(millisLong => ZIO.sleep(millisLong.millis))

    private def typingTimer(messageText: MessageText): IO[WahaError, Unit] =
      for {
        words <- ZIO
          .attempt(messageText.value.length / charactersPerWord)
          .mapError(e => WahaError.unexpectedError("Error calculating words count", Some(e)))
        minutes <- ZIO
          .attempt(words / config.wordsPerMinute)
          .mapError(e => WahaError.unexpectedError("Error calculating typing minutes", Some(e)))
        inconsistentDelaySeconds <- Random.nextDoubleBetween(3, 13)
        typingMillis = ((minutes * 60.0) + inconsistentDelaySeconds) * 1000.0
        _ <- ZIO.sleep(typingMillis.toLong.millis)
      } yield ()

    inline private def setGroupName(sessionID: SessionID, groupID: GroupID, name: GroupName): IO[WahaError, Unit] =
      for {
        _ <- ZIO.logDebug(
          s"Setting group name for sessionID: [$sessionID], groupID [$groupID], name: [$name]"
        )
        _        <- simulateHumanDelay
        response <- GroupsRequests.setName(
          config.baseUri,
          apiKeyHeader,
          sessionID,
          groupID,
          GroupsRequests.GroupsSetNameRequestBody(name),
        )
        _ <- ZIO.logDebug(s"Set group name response: [$response]")
      } yield ()

    inline private def setGroupDescription(
        sessionID: SessionID,
        groupID: GroupID,
        description: GroupDescription,
    ): IO[WahaError, Unit] = for {
      _ <- ZIO.logDebug(
        s"Setting group description for sessionID: [$sessionID], groupID [$groupID], description: [$description]"
      )
      _        <- simulateHumanDelay
      response <- GroupsRequests.setDescription(
        config.baseUri,
        apiKeyHeader,
        sessionID,
        groupID,
        GroupsRequests.GroupsSetDescriptionRequestBody(description),
      )
      _ <- ZIO.logDebug(s"Set group description response: [$response]")
    } yield ()

    inline private def setGroupPicture(sessionID: SessionID, groupID: GroupID, picture: FileType): IO[WahaError, Unit] =
      for {
        _ <- ZIO.logDebug(
          s"Setting group picture for sessionID: [$sessionID], groupID [$groupID], picture: [$picture]"
        )
        _        <- simulateHumanDelay
        response <- GroupsRequests.setPicture(
          config.baseUri,
          apiKeyHeader,
          sessionID,
          groupID,
          GroupsRequests.GroupsSetPictureBody(
            picture.transformInto[GroupsRequests.GroupsFileType]
          ),
        )
        _ <- ZIO.logDebug(s"Set group picture response: [$response]")
      } yield ()

    inline private def getInviteCode(sessionID: SessionID, groupID: GroupID): IO[WahaError, Response[GroupInviteUrl]] =
      for {
        _        <- ZIO.logDebug(s"Getting invite code for sessionID: [$sessionID], groupID [$groupID]")
        _        <- simulateHumanDelay
        response <- GroupsRequests.getInviteCode(
          config.baseUri,
          apiKeyHeader,
          sessionID,
          groupID,
        )
        _ <- ZIO.logDebug(s"Get invite code response: [$response]")
      } yield response

    inline private def startStopTyping(
        sessionID: SessionID,
        chatID: ChatID,
        messageText: MessageText,
    ): IO[WahaError, Unit] = for {
      _             <- simulateHumanDelay
      startResponse <- ChattingRequests.startTyping(
        config.baseUri,
        apiKeyHeader,
        ChattingRequests.ChattingTypingRequestBody(sessionID, chatID),
      )
      _            <- ZIO.logDebug(s"Start typing response: [$startResponse]")
      _            <- typingTimer(messageText)
      stopResponse <- ChattingRequests.stopTyping(
        config.baseUri,
        apiKeyHeader,
        ChattingRequests.ChattingTypingRequestBody(sessionID, chatID),
      )
      _ <- ZIO.logDebug(s"Stop typing response: [$stopResponse]")
    } yield ()

    inline private def sendMessage(input: ChattingMessageInput): IO[WahaError, Unit] =
      for {
        _        <- ZIO.logDebug(s"Sending message [$input]")
        _        <- simulateHumanDelay
        response <- input match {
          case input: ChattingMessageInput.Text =>
            for {
              _        <- startStopTyping(input.sessionID, input.chatID, input.text)
              response <- ChattingRequests.sendText(
                config.baseUri,
                apiKeyHeader,
                input.transformInto[ChattingRequests.ChattingSendTextRequestBody],
              )
            } yield response
          case input: ChattingMessageInput.Image =>
            ChattingRequests.sendImage(
              config.baseUri,
              apiKeyHeader,
              input.transformInto[ChattingRequests.ChattingSendImageRequestBody],
            )
          case input: ChattingMessageInput.File =>
            ChattingRequests.sendFile(
              config.baseUri,
              apiKeyHeader,
              input.transformInto[ChattingRequests.ChattingSendFileRequestBody],
            )
          case input: ChattingMessageInput.Voice =>
            ChattingRequests.sendVoice(
              config.baseUri,
              apiKeyHeader,
              input.transformInto[ChattingRequests.ChattingSendVoiceRequestBody],
            )
          case input: ChattingMessageInput.Video =>
            ChattingRequests.sendVideo(
              config.baseUri,
              apiKeyHeader,
              input.transformInto[ChattingRequests.ChattingSendVideoRequestBody],
            )
        }
        _ <- ZIO.logDebug(s"Send message response: [$response]")
      } yield ()

    inline private def inviteParticipantsPrivately(
        sessionID: SessionID,
        groupID: GroupID,
        name: Option[GroupName],
        participants: NonEmptyList[UserAccountID],
    ): IO[WahaError, Response[GroupInviteUrl]] =
      for {
        _ <- ZIO.logDebug(
          s"Inviting participants to group for sessionID: [$sessionID], groupID [$groupID], participants: [$participants]"
        )
        getGroupInviteCodeResponse <- getInviteCode(sessionID, groupID)
        groupName                  <- name.fold(
          GroupsRequests
            .getGroup(config.baseUri, apiKeyHeader, sessionID, groupID)
            .map(_.body.name)
        )(
          ZIO.succeed(_)
        )
        _ <- participants.toList.traverse { participant =>
          for {
            response <- sendMessage(
              ChattingMessageInput.Text(
                sessionID,
                chatID = ChatID.fromUserAccountID(participant),
                text = MessageText.assume(
                  s"You have been invited in $groupName group. Join using this link: ${getGroupInviteCodeResponse.body}"
                ),
                linkPreview = None,
                linkPreviewHighQuality = None,
                replyToMessageID = None,
              )
            )
            _ <- ZIO.logDebug(s"Invited participant [$participant] with response: [$response]")
          } yield ()
        }
      } yield getGroupInviteCodeResponse

    inline private def addGroupParticipants(
        sessionID: SessionID,
        groupID: GroupID,
        participants: NonEmptyList[UserAccountID],
    ): ZIO[Any, WahaError, Response[GroupsRequests.GroupsAddParticipantsResponseBody]] = for {
      _ <- ZIO.logDebug(
        s"Adding participants to group for sessionID: [$sessionID], groupID [$groupID], participants: [$participants]"
      )
      _        <- simulateHumanDelay
      response <- GroupsRequests.addParticipants(
        config.baseUri,
        apiKeyHeader,
        sessionID,
        groupID,
        GroupsRequests.GroupsAddParticipantsRequestBody(participants.map(GroupsRequests.GroupsParticipant.apply).toList),
      )
      _ <- ZIO.logDebug(s"Add group participants response: [$response]")
    } yield response

    inline private def removeGroupParticipants(
        sessionID: SessionID,
        groupID: GroupID,
        participants: NonEmptyList[UserAccountID],
    ): ZIO[Any, WahaError, Response[String]] = for {
      _ <- ZIO.logDebug(
        s"Removing participants from group for sessionID: [$sessionID], groupID [$groupID], participants: [$participants]"
      )
      _        <- simulateHumanDelay
      response <- GroupsRequests.removeParticipants(
        config.baseUri,
        apiKeyHeader,
        sessionID,
        groupID,
        GroupsRequests.GroupsRemoveParticipantsRequestBody(
          participants.map(GroupsRequests.GroupsParticipant.apply).toList
        ),
      )
      _ <- ZIO.logDebug(s"Remove group participants response: [$response]")
    } yield response

    inline private def promoteGroupParticipants(
        sessionID: SessionID,
        groupID: GroupID,
        participants: NonEmptyList[UserAccountID],
    ): ZIO[Any, WahaError, Response[String]] = for {
      _ <- ZIO.logDebug(
        s"Promoting participants in group for sessionID: [$sessionID], groupID [$groupID], participants: [$participants]"
      )
      _        <- simulateHumanDelay
      response <- GroupsRequests.promoteParticipants(
        config.baseUri,
        apiKeyHeader,
        sessionID,
        groupID,
        GroupsRequests.GroupsPromoteParticipantsRequestBody(
          participants.map(GroupsRequests.GroupsParticipant.apply).toList
        ),
      )
      _ <- ZIO.logDebug(s"Promote group participants response: [$response]")
    } yield response

    inline private def demoteGroupParticipants(
        sessionID: SessionID,
        groupID: GroupID,
        participants: NonEmptyList[UserAccountID],
    ): ZIO[Any, WahaError, Response[String]] = for {
      _ <- ZIO.logDebug(
        s"Demoting participants in group for sessionID: [$sessionID], groupID [$groupID], participants: [$participants]"
      )
      _        <- simulateHumanDelay
      response <- GroupsRequests.demoteParticipants(
        config.baseUri,
        apiKeyHeader,
        sessionID,
        groupID,
        GroupsRequests.GroupsDemoteParticipantsRequestBody(
          participants.map(GroupsRequests.GroupsParticipant.apply).toList
        ),
      )
      _ <- ZIO.logDebug(s"Demote group participants response: [$response]")
    } yield response

    private def checkUserAccountIsRegistered(
        sessionID: SessionID,
        userAccountIDs: List[UserAccountID],
    ): IO[WahaError, UserAccountsCheckResult] =
      for {
        checkIfUserAccountsExist <- ZIO.foreachPar(userAccountIDs)(participant =>
          ContactsRequests
            .checkIfExists(
              config.baseUri,
              apiKeyHeader,
              sessionID,
              participant,
            )
            .map(_.body)
            .map(response => (response.numberExists, response.userAccountID.getOrElse(participant)))
        )
        (registeredUserAccountIDs, nonRegisteredUserAccountIDs) = checkIfUserAccountsExist.partitionMap {
          case (false, nonRegistered) => Right(nonRegistered)
          case (true, registered)     => Left(registered)
        }
      } yield (registered = registeredUserAccountIDs, nonRegistered = nonRegisteredUserAccountIDs)

    private def collectFailedToAddParticipants(
        participants: List[GroupNewParticipant]
    ): List[UserAccountID] =
      participants.collect {
        case newAddedParticipant if !GroupParticipantError.nonRetryableErrors.contains(newAddedParticipant.errorCode) =>
          UserAccountID.fromWhatsAppPhoneNumber(newAddedParticipant.phoneNumber)
      }

    override def groupsCreate(input: GroupsCreateInput): IO[WahaError, GroupsCreateOutput] =
      for {
        _ <- ZIO.logDebug(s"WahaClient.groupsCreate called with input: $input")
        (registeredUserAccountIDs, nonRegisteredUserAccountIDs) <- checkUserAccountIsRegistered(
          input.sessionID,
          input.participants,
        )
        groupsCreateResponse <- GroupsRequests.create(
          config.baseUri,
          apiKeyHeader,
          input.sessionID,
          input
            .into[GroupsRequests.GroupsCreateRequestBody]
            .withFieldConst(_.participants, registeredUserAccountIDs.map(GroupsRequests.GroupsParticipant.apply))
            .transform,
        )
        _ <- ZIO.logDebug(s"Group created with response: [$groupsCreateResponse]")
        _ <- input.description.traverse(setGroupDescription(input.sessionID, groupsCreateResponse.body.groupID, _))
        _ <- input.picture.traverse(setGroupPicture(input.sessionID, groupsCreateResponse.body.groupID, _))
        failedGroupParticipants = collectFailedToAddParticipants(groupsCreateResponse.body.participants)
        maybeGroupInviteUrl <- NonEmptyList
          .fromList(failedGroupParticipants)
          .traverse(participants =>
            inviteParticipantsPrivately(
              input.sessionID,
              groupsCreateResponse.body.groupID,
              Some(input.name),
              participants,
            )
          )
        groupInviteUrl <- maybeGroupInviteUrl.fold(getInviteCode(input.sessionID, groupsCreateResponse.body.groupID))(
          ZIO.succeed(_)
        )
      } yield GroupsCreateOutput(
        groupID = groupsCreateResponse.body.groupID,
        inviteUrl = groupInviteUrl.body,
        nonRegisteredUserAccountIDs = nonRegisteredUserAccountIDs,
      )

    override def chattingSendMessage(input: ChattingMessageInput): IO[WahaError, Unit] = for {
      _      <- ZIO.logDebug(s"WahaClient.sendMessage called with input: [$input]")
      result <- sendMessage(input)
    } yield result

    override def groupsUpdate(input: GroupsUpdateInput): IO[WahaError, GroupsUpdateOutput] = for {
      _ <- ZIO.logDebug(s"WahaClient.groupUpdate called with input: [$input]")
      _ <- input.name.traverse(name => setGroupName(input.sessionID, input.groupID, name))
      _ <- input.description.traverse(description => setGroupDescription(input.sessionID, input.groupID, description))
      _ <- input.picture.traverse(picture => setGroupPicture(input.sessionID, input.groupID, picture))
      (registeredAddUserAccountIDs, nonRegisteredAddUserAccountIDs) <- checkUserAccountIsRegistered(
        input.sessionID,
        input.addParticipants,
      )
      _ <- ZIO.when(nonRegisteredAddUserAccountIDs.nonEmpty)(
        ZIO.logError(
          s"Some addParticipants were not registered: [$nonRegisteredAddUserAccountIDs]. They will be invited privately."
        )
      )
      maybeAddGroupParticipantsResponse <- NonEmptyList
        .fromList(registeredAddUserAccountIDs)
        .traverse(addParticipants => addGroupParticipants(input.sessionID, input.groupID, addParticipants))
      failedGroupParticipants =
        maybeAddGroupParticipantsResponse.fold(List.empty[UserAccountID])(addGroupParticipantsResponse =>
          collectFailedToAddParticipants(addGroupParticipantsResponse.body.participants)
        )
      _ <- NonEmptyList
        .fromList(failedGroupParticipants)
        .traverse(participants =>
          inviteParticipantsPrivately(
            input.sessionID,
            input.groupID,
            input.name,
            participants,
          )
        )
      (registeredRemoveUserAccountIDs, nonRegisteredRemoveUserAccountIDs) <- checkUserAccountIsRegistered(
        input.sessionID,
        input.removeParticipants,
      )
      _ <- NonEmptyList
        .fromList(registeredRemoveUserAccountIDs)
        .traverse(removeParticipants => removeGroupParticipants(input.sessionID, input.groupID, removeParticipants))
      _ <- ZIO.when(nonRegisteredRemoveUserAccountIDs.nonEmpty)(
        ZIO.logError(
          s"Some removeParticipants were not registered: [$nonRegisteredRemoveUserAccountIDs]. Cannot remove unregistered participants from group."
        )
      )
      (registeredPromoteUserAccountIDs, nonRegisteredPromoteUserAccountIDs) <- checkUserAccountIsRegistered(
        input.sessionID,
        input.promoteParticipants,
      )
      _ <- NonEmptyList
        .fromList(registeredPromoteUserAccountIDs)
        .traverse(promoteParticipants => promoteGroupParticipants(input.sessionID, input.groupID, promoteParticipants))
      _ <- ZIO.when(nonRegisteredPromoteUserAccountIDs.nonEmpty)(
        ZIO.logError(
          s"Some promoteParticipants were not registered: [$nonRegisteredPromoteUserAccountIDs]. Cannot promote unregistered participants in group."
        )
      )
      (registeredDemoteUserAccountIDs, nonRegisteredDemoteUserAccountIDs) <- checkUserAccountIsRegistered(
        input.sessionID,
        input.demoteParticipants,
      )
      _ <- NonEmptyList
        .fromList(registeredDemoteUserAccountIDs)
        .traverse(demoteParticipants => demoteGroupParticipants(input.sessionID, input.groupID, demoteParticipants))
      _ <- ZIO.when(nonRegisteredDemoteUserAccountIDs.nonEmpty)(
        ZIO.logError(
          s"Some demoteParticipants were not registered: [$nonRegisteredDemoteUserAccountIDs]. Cannot demote unregistered participants in group."
        )
      )
    } yield GroupsUpdateOutput(
      nonRegisteredUserAccountIDs = nonRegisteredAddUserAccountIDs
    )

    override def groupsLeave(input: GroupsLeaveInput): IO[WahaError, Unit] =
      for {
        _        <- ZIO.logDebug(s"Leaving group with input: [$input]")
        response <- GroupsRequests.leave(
          config.baseUri,
          apiKeyHeader,
          input.sessionID,
          input.groupID,
        )
        _ <- ZIO.logDebug(s"Leave group response: [$response]")
      } yield ()

    override def groupsInviteCode(input: GroupsInviteCodeInput): IO[WahaError, GroupsInviteCodeOutput] = for {
      _        <- ZIO.logDebug(s"Getting invite code with input: [$input]")
      response <- GroupsRequests.getInviteCode(
        config.baseUri,
        apiKeyHeader,
        input.sessionID,
        input.groupID,
      )
      _ <- ZIO.logDebug(s"Get invite code response: [$response]")
    } yield GroupsInviteCodeOutput(inviteUrl = response.body)

    override def groupsGetInfo(input: GroupsGetInfoInput): IO[WahaError, GroupsGetInfoOutput] = for {
      _                      <- ZIO.logDebug(s"Getting group info with input: [$input]")
      groupsGetGroupResponse <- GroupsRequests.getGroup(
        config.baseUri,
        apiKeyHeader,
        input.sessionID,
        input.groupID,
      )
      _                               <- ZIO.logDebug(s"Get group info response: [$groupsGetGroupResponse]")
      groupsGetParticipantsV2Response <- GroupsRequests.getParticipantsV2(
        config.baseUri,
        apiKeyHeader,
        input.sessionID,
        input.groupID,
      )
      _                        <- ZIO.logDebug(s"Get group participants response: [$groupsGetParticipantsV2Response]")
      groupsGetPictureResponse <- GroupsRequests.getPicture(
        config.baseUri,
        apiKeyHeader,
        input.sessionID,
        input.groupID,
      )
    } yield GroupsGetInfoOutput(
      groupsGetGroupResponse.body.groupID,
      groupsGetGroupResponse.body.name,
      groupsGetGroupResponse.body.ownerUserID,
      UserAccountID.fromWhatsAppPhoneNumber(groupsGetGroupResponse.body.ownerPhoneNumber),
      groupsGetGroupResponse.body.ownerPhoneNumber,
      groupsGetGroupResponse.body.description,
      groupsGetPictureResponse.body.pictureUrl,
      groupsGetParticipantsV2Response.body.participants.map(_.transformInto[GroupParticipant]),
    )
  }

  private def observed(service: WahaClient): WahaClient = service

  val live = ZLayer.derive[WahaClientImpl] >>> ZLayer.fromFunction(observed)
}
