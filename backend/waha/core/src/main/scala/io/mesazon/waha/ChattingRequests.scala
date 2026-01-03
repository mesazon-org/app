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

object ChattingRequests {

  given JsonValueCodec[ChattingSendTextRequestBody]  = JsonCodecMaker.make
  given JsonValueCodec[ChattingTypingRequestBody]    = JsonCodecMaker.make
  given JsonValueCodec[ChattingSendImageRequestBody] = JsonCodecMaker.make
  given JsonValueCodec[ChattingSendFileRequestBody]  = JsonCodecMaker.make
  given JsonValueCodec[ChattingSendVoiceRequestBody] = JsonCodecMaker.make
  given JsonValueCodec[ChattingSendVideoRequestBody] = JsonCodecMaker.make

  given JsonValueCodec[ChattingSendTextResponseBody] = JsonCodecMaker.make

  // Common
  sealed trait ChattingFileType

  object ChattingFileType {
    case class Url(
        @named("mimetype") mimeType: FileTypeMimeType,
        @named("filename") fileName: FileTypeFileName,
        url: FileTypeURL,
    ) extends ChattingFileType

    case class Data(
        @named("mimetype") mimeType: FileTypeMimeType,
        @named("filename") fileName: FileTypeFileName,
        data: FileTypeData,
    ) extends ChattingFileType
  }

  // Request Bodies
  case class ChattingTypingRequestBody(
      @named("session") sessionID: SessionID,
      @named("chatId") chatID: ChatID,
  )

  case class ChattingSendTextRequestBody(
      @named("session") sessionID: SessionID,
      @named("chatId") chatID: ChatID,
      text: MessageText,
      linkPreview: Option[Boolean] = None,
      linkPreviewHighQuality: Option[Boolean] = None, // For this to work both needs to be true
      @named("reply_to") replyToMessageID: Option[MessageID] = None,
  )

  case class ChattingSendImageRequestBody(
      @named("session") sessionID: SessionID,
      @named("chatId") chatID: ChatID,
      file: ChattingFileType,
      caption: Option[MessageCaption] = None,
      @named("reply_to") replyToMessageID: Option[MessageID] = None,
  )

  case class ChattingSendFileRequestBody(
      @named("session") sessionID: SessionID,
      @named("chatId") chatID: ChatID,
      file: ChattingFileType,
      caption: Option[MessageCaption] = None,
      @named("reply_to") replyToMessageID: Option[MessageID] = None,
  )

  case class ChattingSendVoiceRequestBody(
      @named("session") sessionID: SessionID,
      @named("chatId") chatID: ChatID,
      file: ChattingFileType,
      convert: Boolean = true,
      @named("reply_to") replyToMessageID: Option[MessageID] = None,
  )

  case class ChattingSendVideoRequestBody(
      @named("session") sessionID: SessionID,
      @named("chatId") chatID: ChatID,
      file: ChattingFileType,
      convert: Boolean = true,
      asNote: Option[Boolean] = None,
      caption: Option[MessageCaption] = None,
      @named("reply_to") replyToMessageID: Option[MessageID] = None,
  )

  // Response Bodies
  case class ChattingSendTextResponseBody(
      @named("id") messageID: MessageID
  )

  def startTyping(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingTypingRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .post(baseUri.withPath("api", "startTyping"))
      .body(asJson(body))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_START_TYPING_ERROR)

  def stopTyping(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingTypingRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .post(baseUri.withPath("api", "stopTyping"))
      .body(asJson(body))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_STOP_TYPING_ERROR)

  def sendText(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingSendTextRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .body(asJson(body))
      .post(baseUri.withPath("api", "sendText"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_SEND_TEXT_ERROR)

  def sendImage(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingSendImageRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .body(asJson(body))
      .post(baseUri.withPath("api", "sendImage"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_SEND_IMAGE_ERROR)

  def sendFile(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingSendFileRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .body(asJson(body))
      .post(baseUri.withPath("api", "sendFile"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_SEND_FILE_ERROR)

  def sendVoice(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingSendVoiceRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .body(asJson(body))
      .post(baseUri.withPath("api", "sendVoice"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_SEND_VOICE_ERROR)

  def sendVideo(
      baseUri: Uri,
      apiKeyHeader: Header,
      body: ChattingSendVideoRequestBody,
  )(using Backend[Task]): IO[WahaError, Response[String]] =
    basicRequest
      .body(asJson(body))
      .post(baseUri.withPath("api", "sendVoice"))
      .headers(apiKeyHeader)
      .response(asString)
      .standardSendRequestString(WahaErrorCode.CHATTING_SEND_VIDEO_ERROR)
}
