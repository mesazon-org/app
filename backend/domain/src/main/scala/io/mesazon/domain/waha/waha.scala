package io.mesazon.domain.waha

import io.github.iltotore.iron.RefinedType
import io.mesazon.domain.*

type GroupName = GroupName.T
object GroupName extends RefinedType[String, NonEmptyTrimmedCap]

type GroupID = GroupID.T
object GroupID extends RefinedType[String, WahaGroupIDPredicate]

type GroupDescription = GroupDescription.T
object GroupDescription extends RefinedType[String, NonEmptyTrimmedCap]

type FileTypeMimeType = FileTypeMimeType.T
object FileTypeMimeType extends RefinedType[String, NonEmptyTrimmedCap]

type FileTypeFileName = FileTypeFileName.T
object FileTypeFileName extends RefinedType[String, NonEmptyTrimmedCap]

type FileTypeURL = FileTypeURL.T
object FileTypeURL extends RefinedType[String, NonEmptyTrimmedCap]

type FileTypeData = FileTypeData.T
object FileTypeData extends RefinedType[String, NonEmptyTrimmedCap]

type WahaPhoneNumber = WahaPhone.T
object WahaPhone extends RefinedType[String, NonEmptyTrimmedLowerCase] {
  def fromE164(phoneNumber: String): T =
    assume(phoneNumber.drop(1)) // drop the '+' sign

  def fromUserAccountID(userAccountID: UserAccountID): T =
    assume(userAccountID.value.takeWhile(_ != '@'))

  def fromWhatsAppPhoneNumber(whatsAppPhoneNumber: WhatsAppPhoneNumber): T =
    assume(whatsAppPhoneNumber.value.takeWhile(_ != '@'))
}

type UserAccountID = UserAccountID.T
object UserAccountID extends RefinedType[String, WahaIDPredicate] {
  def fromE164(phoneNumber: String): T =
    assume(s"${phoneNumber.drop(1)}@c.us") // drop the '+' sign

  def fromChatID(chatID: ChatID): T =
    assume(chatID.value)

  def fromWhatsAppPhoneNumber(whatsAppPhoneNumber: WhatsAppPhoneNumber): T =
    assume(whatsAppPhoneNumber.value.replace("@s.whatsapp.net", "@c.us"))
}

type WhatsAppPhoneNumber = WhatsAppPhoneNumber.T
object WhatsAppPhoneNumber extends RefinedType[String, WhatsappIDPredicate] {
  def fromE164(phoneNumber: String): T =
    assume(s"${phoneNumber.drop(1)}@s.whatsapp.net") // drop the '+' sign

  def fromUserAccountID(userAccountID: UserAccountID): T =
    assume(s"${userAccountID.value.takeWhile(_ != '@')}@s.whatsapp.net")

  def fromChatID(chatID: ChatID): T =
    assume(s"${chatID.value.takeWhile(_ != '@')}@s.whatsapp.net")
}

type UserID = UserID.T
object UserID extends RefinedType[String, WahaUserIDPredicate]

type ChatID = ChatID.T
object ChatID extends RefinedType[String, WahaIDPredicate] {
  def fromE164(phoneNumber: String): T =
    assume(s"${phoneNumber.drop(1)}@c.us") // drop the '+' sign

  def fromWhatsAppPhoneNumber(whatsAppPhoneNumber: WhatsAppPhoneNumber): T =
    assume(whatsAppPhoneNumber.value.replace("@s.whatsapp.net", "@c.us"))

  def fromUserAccountID(userAccountID: UserAccountID): T =
    assume(userAccountID.value)

  def fromGroupID(groupID: GroupID): T =
    assume(groupID.value)
}

type MessageID = MessageID.T
object MessageID extends RefinedType[String, NonEmptyTrimmedCap]

type SessionID = SessionID.T
object SessionID extends RefinedType[String, NonEmptyTrimmedCap]

type GroupInviteUrl = GroupInviteUrl.T
object GroupInviteUrl extends RefinedType[String, NonEmptyTrimmedCap]

type GroupPictureUrl = GroupPictureUrl.T
object GroupPictureUrl extends RefinedType[String, NonEmptyTrimmedCap]

type MessageText = MessageText.T
object MessageText extends RefinedType[String, NonEmpty]

object FullName extends RefinedType[String, NonEmptyTrimmedCap]
type FullName = FullName.T

type MessageCaption = MessageCaption.T
object MessageCaption extends RefinedType[String, NonEmptyTrimmedCap]
