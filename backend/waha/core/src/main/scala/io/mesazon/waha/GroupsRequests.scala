package io.mesazon.waha

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.waha.domain.*
import sttp.client4.*
import sttp.client4.jsoniter.*
import sttp.model.*
import zio.*

import scala.language.postfixOps

object GroupsRequests {

  given JsonValueCodec[GroupsFileType]      = JsonCodecMaker.make
  given JsonValueCodec[GroupsParticipant]   = JsonCodecMaker.make
  given JsonValueCodec[GroupsParticipantV2] = JsonCodecMaker.make
  given JsonValueCodec[GroupNewParticipant] = JsonCodecMaker.make

  given JsonValueCodec[GroupsCreateRequestBody]              = JsonCodecMaker.make
  given JsonValueCodec[GroupsSetDescriptionRequestBody]      = JsonCodecMaker.make
  given JsonValueCodec[GroupsSetPictureBody]                 = JsonCodecMaker.make
  given JsonValueCodec[GroupsSetNameRequestBody]             = JsonCodecMaker.make
  given JsonValueCodec[GroupsAddParticipantsRequestBody]     = JsonCodecMaker.make
  given JsonValueCodec[GroupsRemoveParticipantsRequestBody]  = JsonCodecMaker.make
  given JsonValueCodec[GroupsPromoteParticipantsRequestBody] = JsonCodecMaker.make
  given JsonValueCodec[GroupsDemoteParticipantsRequestBody]  = JsonCodecMaker.make

  given JsonValueCodec[GroupsCreateResponseBody]            = JsonCodecMaker.make
  given JsonValueCodec[GroupsGetPictureResponseBody]        = JsonCodecMaker.make
  given JsonValueCodec[GroupsGetGroupResponseBody]          = JsonCodecMaker.make
  given JsonValueCodec[GroupsGetParticipantsV2ResponseBody] = new JsonValueCodec[GroupsGetParticipantsV2ResponseBody] {
    val listCodec = JsonCodecMaker.make[List[GroupsParticipantV2]]

    override def decodeValue(
        in: JsonReader,
        default: GroupsGetParticipantsV2ResponseBody,
    ): GroupsGetParticipantsV2ResponseBody =
      GroupsGetParticipantsV2ResponseBody(listCodec.decodeValue(in, default.participants))

    override def encodeValue(x: GroupsGetParticipantsV2ResponseBody, out: JsonWriter): Unit =
      listCodec.encodeValue(x.participants, out)

    override def nullValue: GroupsGetParticipantsV2ResponseBody = GroupsGetParticipantsV2ResponseBody(
      listCodec.nullValue
    )
  }

  given JsonValueCodec[GroupsAddParticipantsResponseBody] = new JsonValueCodec[GroupsAddParticipantsResponseBody] {
    val listCodec = JsonCodecMaker.make[List[GroupNewParticipant]]

    override def decodeValue(
        in: JsonReader,
        default: GroupsAddParticipantsResponseBody,
    ): GroupsAddParticipantsResponseBody =
      GroupsAddParticipantsResponseBody(listCodec.decodeValue(in, default.participants))

    override def encodeValue(x: GroupsAddParticipantsResponseBody, out: JsonWriter): Unit =
      listCodec.encodeValue(x.participants, out)

    override def nullValue: GroupsAddParticipantsResponseBody = GroupsAddParticipantsResponseBody(
      listCodec.nullValue
    )
  }

  given JsonValueCodec[GroupParticipantError] = new JsonValueCodec[GroupParticipantError] {
    override def decodeValue(
        in: JsonReader,
        default: GroupParticipantError,
    ): GroupParticipantError =
      GroupParticipantError(in.readInt())

    override def encodeValue(x: GroupParticipantError, out: JsonWriter): Unit =
      out.writeVal(x.errorCode)

    override def nullValue: GroupParticipantError = null
  }

  // Common
  sealed trait GroupsFileType

  object GroupsFileType {
    case class Url(
        @named("mimetype") mimeType: FileTypeMimeType,
        @named("filename") fileName: FileTypeFileName,
        url: FileTypeURL,
    ) extends GroupsFileType

    case class Data(
        @named("mimetype") mimeType: FileTypeMimeType,
        @named("filename") fileName: FileTypeFileName,
        data: FileTypeData,
    ) extends GroupsFileType
  }

  case class GroupsParticipant(
      @named("id") userAccountID: UserAccountID
  )

  case class GroupsParticipantV2(
      @named("id") userID: UserID,
      @named("pn") userAccountID: UserAccountID,
      role: GroupParticipantRole,
  )

  case class GroupNewParticipant(
      @named("JID") userID: UserID,
      @named("PhoneNumber") phoneNumber: WhatsAppPhoneNumber,
      @named("Error") errorCode: GroupParticipantError,
  )

  // Request Bodies
  case class GroupsCreateRequestBody(
      name: GroupName,
      participants: List[GroupsParticipant],
  )

  case class GroupsSetDescriptionRequestBody(
      description: GroupDescription
  )

  case class GroupsSetNameRequestBody(
      @named("subject") name: GroupName
  )

  case class GroupsSetPictureBody(file: GroupsFileType)

  case class GroupsAddParticipantsRequestBody(
      participants: List[GroupsParticipant]
  )

  case class GroupsRemoveParticipantsRequestBody(
      participants: List[GroupsParticipant]
  )

  case class GroupsPromoteParticipantsRequestBody(
      participants: List[GroupsParticipant]
  )

  case class GroupsDemoteParticipantsRequestBody(
      participants: List[GroupsParticipant]
  )

  // Response Bodies
  case class GroupsCreateResponseBody(
      @named("JID") groupID: GroupID,
      @named("Name") name: GroupName,
      @named("Participants") participants: List[GroupNewParticipant],
  )

  case class GroupsGetPictureResponseBody(
      @named("url") pictureUrl: Option[GroupPictureUrl]
  )

  case class GroupsGetParticipantsV2ResponseBody(
      participants: List[GroupsParticipantV2]
  )

  case class GroupsAddParticipantsResponseBody(
      participants: List[GroupNewParticipant]
  )

  case class GroupsGetGroupResponseBody(
      @named("JID") groupID: GroupID,
      @named("OwnerJID") ownerUserID: UserID,
      @named("OwnerPN") ownerPhoneNumber: WhatsAppPhoneNumber,
      @named("Name") name: GroupName,
      @named("Topic") description: Option[GroupDescription],
  )

  def create(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      body: GroupsCreateRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[GroupsCreateResponseBody]] =
    basicRequest
      .body(asJson(body))
      .post(baseUri.withPath("api", sessionID.value, "groups"))
      .headers(apiKeyHeader)
      .response(asJson[GroupsCreateResponseBody])
      .standardSendRequest(WahaErrorCode.GROUPS_CREATE_NEW_ERROR)

  def getInviteCode(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[GroupInviteUrl]] =
    basicRequest
      .get(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "invite-code"))
      .headers(apiKeyHeader)
      .response(asString)
      .mapResponse(_.flatMap(GroupInviteUrl.either))
      .standardSendRequestString(WahaErrorCode.GROUPS_INVITE_CODE_ERROR)

  def getInviteCodeRevoke(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[GroupInviteUrl]] =
    basicRequest
      .post(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "invite-code", "revoke"))
      .headers(apiKeyHeader)
      .response(asString)
      .mapResponse(_.flatMap(GroupInviteUrl.either))
      .standardSendRequestString(WahaErrorCode.GROUPS_INVITE_CODE_REVOKE_ERROR)

  def setDescription(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsSetDescriptionRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .put(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "description"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_SET_DESCRIPTION_ERROR)

  def setPicture(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsSetPictureBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .put(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "picture"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_SET_PICTURE_ERROR)

  def delete(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .delete(baseUri.withPath("api", sessionID.value, "groups", groupID.value))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_DELETE_ERROR)

  def leave(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .post(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "leave"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_LEAVE_ERROR)

  def setName(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsSetNameRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .put(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "subject"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_SET_NAME_ERROR)

  def addParticipants(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsAddParticipantsRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[GroupsAddParticipantsResponseBody]] =
    basicRequest
      .post(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "participants", "add"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asJson[GroupsAddParticipantsResponseBody])
      .standardSendRequest(WahaErrorCode.GROUPS_ADD_PARTICIPANT_ERROR)

  def removeParticipants(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsRemoveParticipantsRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .post(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "participants", "remove"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_REMOVE_PARTICIPANT_ERROR)

  def promoteParticipants(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsPromoteParticipantsRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .post(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "admin", "promote"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_PROMOTE_PARTICIPANT_ERROR)

  def demoteParticipants(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
      body: GroupsDemoteParticipantsRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .post(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "admin", "demote"))
      .headers(apiKeyHeader)
      .body(asJson(body))
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_DEMOTE_PARTICIPANT_ERROR)

  def getPicture(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[GroupsGetPictureResponseBody]] =
    basicRequest
      .get(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "picture"))
      .headers(apiKeyHeader)
      .response(asJson[GroupsGetPictureResponseBody])
      .standardSendRequest(WahaErrorCode.GROUPS_GET_PICTURE_ERROR)

  def getParticipantsV2(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[GroupsGetParticipantsV2ResponseBody]] =
    basicRequest
      .get(baseUri.withPath("api", sessionID.value, "groups", groupID.value, "participants", "v2"))
      .headers(apiKeyHeader)
      .response(asJson[GroupsGetParticipantsV2ResponseBody])
      .standardSendRequest(WahaErrorCode.GROUPS_GET_PARTICIPANTS_V2_ERROR)

  def getGroups(
      baseUri: Uri,
      apiKeyHeader: Header,
      session: String,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .get(baseUri.withPath("api", session, "groups"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.GROUPS_GET_ERROR)

  def getGroup(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      groupID: GroupID,
  )(using Backend[Task]): IO[WahaError, Response[GroupsGetGroupResponseBody]] =
    basicRequest
      .get(baseUri.withPath("api", sessionID.value, "groups", groupID.value))
      .headers(apiKeyHeader)
      .response(asJson[GroupsGetGroupResponseBody])
      .standardSendRequest(WahaErrorCode.GROUPS_GET_GROUP_ERROR)
}
