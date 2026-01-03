package io.mesazon.waha

import io.github.iltotore.iron.*
import io.github.iltotore.iron.constraint.all.*

package object domain {
  type NonEmptyTrimmedLowerCase = Trimmed & LettersLowerCase & MinLength[1]
  type NonEmptyTrimmed          = Trimmed & MinLength[1]

  type GroupName = GroupName.T
  object GroupName extends RefinedType[String, NonEmptyTrimmed]

  type GroupID = GroupID.T
  object GroupID extends RefinedType[String, NonEmptyTrimmedLowerCase]

  type GroupDescription = GroupDescription.T
  object GroupDescription extends RefinedType[String, NonEmptyTrimmed]

  type FileTypeMimeType = FileTypeMimeType.T
  object FileTypeMimeType extends RefinedType[String, NonEmptyTrimmed]

  type FileTypeFileName = FileTypeFileName.T
  object FileTypeFileName extends RefinedType[String, NonEmptyTrimmed]

  type FileTypeURL = FileTypeURL.T
  object FileTypeURL extends RefinedType[String, NonEmptyTrimmed]

  type FileTypeData = FileTypeData.T
  object FileTypeData extends RefinedType[String, NonEmptyTrimmed]

  type WahaPhone = WahaPhone.T
  object WahaPhone extends RefinedType[String, NonEmptyTrimmedLowerCase] {
    def fromE164(phoneNumber: String): T =
      assume(phoneNumber.drop(1)) // drop the '+' sign

    def fromUserAccountID(userAccountID: UserAccountID): T =
      assume(userAccountID.value.takeWhile(_ != '@'))

    def fromWhatsAppPhoneNumber(whatsAppPhoneNumber: WhatsAppPhoneNumber): T =
      assume(whatsAppPhoneNumber.value.takeWhile(_ != '@'))
  }

  type UserAccountID = UserAccountID.T
  object UserAccountID extends RefinedType[String, NonEmptyTrimmedLowerCase] {
    def fromE164(phoneNumber: String): T =
      assume(s"${phoneNumber.drop(1)}@c.us") // drop the '+' sign

    def fromChatID(chatID: ChatID): T =
      assume(chatID.value)

    def fromWhatsAppPhoneNumber(whatsAppPhoneNumber: WhatsAppPhoneNumber): T =
      assume(whatsAppPhoneNumber.value.replace("@s.whatsapp.net", "@c.us"))
  }

  type WhatsAppPhoneNumber = WhatsAppPhoneNumber.T
  object WhatsAppPhoneNumber extends RefinedType[String, NonEmptyTrimmedLowerCase] {
    def fromE164(phoneNumber: String): T =
      assume(s"${phoneNumber.drop(1)}@s.whatsapp.net") // drop the '+' sign

    def fromUserAccountID(userAccountID: UserAccountID): T =
      assume(s"${userAccountID.value.takeWhile(_ != '@')}@s.whatsapp.net")

    def fromChatID(chatID: ChatID): T =
      assume(s"${chatID.value.takeWhile(_ != '@')}@s.whatsapp.net")
  }

  type UserID = UserID.T
  object UserID extends RefinedType[String, NonEmptyTrimmedLowerCase]

  type ChatID = ChatID.T
  object ChatID extends RefinedType[String, NonEmptyTrimmedLowerCase] {
    def fromE164(phoneNumber: String): T =
      assume(s"${phoneNumber.drop(1)}@c.us") // drop the '+' sign

    def fromUserAccountID(userAccountID: UserAccountID): T =
      assume(userAccountID.value)

    def fromGroupID(groupID: GroupID): T =
      assume(groupID.value)
  }

  type MessageID = MessageID.T
  object MessageID extends RefinedType[String, NonEmptyTrimmed]

  type SessionID = SessionID.T
  object SessionID extends RefinedType[String, NonEmptyTrimmed]

  type GroupInviteUrl = GroupInviteUrl.T
  object GroupInviteUrl extends RefinedType[String, NonEmptyTrimmed]

  type GroupPictureUrl = GroupPictureUrl.T
  object GroupPictureUrl extends RefinedType[String, NonEmptyTrimmed]

  type MessageText = MessageText.T
  object MessageText extends RefinedType[String, NonEmptyTrimmed]

  type MessageCaption = MessageCaption.T
  object MessageCaption extends RefinedType[String, NonEmptyTrimmed]
}
