package io.rikkos.gateway.validation

import cats.syntax.all.*
import io.rikkos.domain.gateway.*
import io.rikkos.domain.waha
import io.rikkos.gateway.smithy
import io.rikkos.gateway.validation.PhoneNumberValidator.PhoneNumberParams
import zio.*

object WahaValidator {

  private def wahaMessageRequestValidator(
      phoneNumberValidator: DomainValidator[PhoneNumberParams, PhoneNumberE164]
  ): DomainValidator[smithy.WahaMessageTextRequest, WahaMessage] = { request =>
    val fields = List(request.payload.from, request.payload.data.info.sender, request.payload.data.info.senderAlt)

    val validateWahaUserAccountID =
      validateRequiredFields("userAccountID", fields, waha.UserAccountID.either)
    val validateWahaWhatsAppPhoneNumber =
      validateRequiredFields("whatsAppPhoneNumber", fields, waha.WhatsAppPhoneNumber.either)
    val validateWahaChatID =
      validateRequiredFields("chatID", fields, waha.ChatID.either)
    val validateWahaWhatsAppPhoneNumberCal =
      validateWahaUserAccountID
        .map(waha.WhatsAppPhoneNumber.fromUserAccountID)
        .orElse(validateWahaChatID.map(waha.WhatsAppPhoneNumber.fromChatID))
        .orElse(validateWahaWhatsAppPhoneNumber)
    val validateWahaUserAccountIDCal =
      validateWahaWhatsAppPhoneNumber
        .map(waha.UserAccountID.fromWhatsAppPhoneNumber)
        .orElse(validateWahaChatID.map(waha.UserAccountID.fromChatID))
        .orElse(validateWahaUserAccountID)
    val validateWahaChatIDCal =
      validateWahaUserAccountID
        .map(waha.ChatID.fromUserAccountID)
        .orElse(validateWahaWhatsAppPhoneNumber.map(waha.ChatID.fromWhatsAppPhoneNumber))
        .orElse(validateWahaChatID)

    validateWahaUserAccountIDCal.toOption
      .map(userAccountID => phoneNumberValidator.validate(("CY", userAccountID.value)))
      .getOrElse(phoneNumberValidator.validate(("CY", "")))
      .map { phoneNumberValidator =>
        (
          validateRequiredFields("userID", fields, waha.UserID.either),
          validateRequiredField("messageID", request.payload.id, waha.MessageID.either),
          validateWahaChatIDCal,
          validateWahaUserAccountIDCal,
          validateWahaWhatsAppPhoneNumberCal,
          validateRequiredField("fullName", request.payload.data.info.pushName, waha.FullName.either),
          validateRequiredField("messageText", request.payload.body, waha.MessageText.either),
          phoneNumberValidator,
        ).mapN(WahaMessage.apply)
      }
  }

  val wahaMessageRequestValidatorLive = ZLayer.fromFunction(wahaMessageRequestValidator andThen toServiceValidator)
}
