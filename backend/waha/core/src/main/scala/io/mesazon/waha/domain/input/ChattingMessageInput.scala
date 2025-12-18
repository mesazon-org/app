package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

sealed trait ChattingMessageInput {
  def sessionID: SessionID
  def chatID: ChatID
}

object ChattingMessageInput {
  case class Text(
      sessionID: SessionID,
      chatID: ChatID,
      text: MessageText,
      linkPreview: Option[Boolean],
      linkPreviewHighQuality: Option[Boolean],
      replyToMessageID: Option[MessageID],
  ) extends ChattingMessageInput

  case class Image(
      sessionID: SessionID,
      chatID: ChatID,
      file: FileType,
      caption: Option[MessageCaption],
      replyToMessageID: Option[MessageID],
  ) extends ChattingMessageInput

  case class File(
      sessionID: SessionID,
      chatID: ChatID,
      file: FileType,
      caption: Option[MessageCaption],
      replyToMessageID: Option[MessageID],
  ) extends ChattingMessageInput

  case class Voice(
      sessionID: SessionID,
      chatID: ChatID,
      file: FileType,
      convert: Boolean,
      replyToMessageID: Option[MessageID],
  ) extends ChattingMessageInput

  case class Video(
      sessionID: SessionID,
      chatID: ChatID,
      file: FileType,
      convert: Boolean,
      asNote: Option[Boolean],
      caption: Option[MessageCaption],
      replyToMessageID: Option[MessageID],
  ) extends ChattingMessageInput
}
