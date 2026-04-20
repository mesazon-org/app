package io.mesazon.gateway.validation.service

import cats.data.NonEmptyChain
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.{DomainValidator, WahaPhoneNumberDomainValidator}
import zio.*

final class WahaServiceValidator(
    wahaPhoneNumberValidator: WahaPhoneNumberDomainValidator
) extends ServiceValidator[smithy.WahaMessageTextRequest, WahaMessage] {

  val domainValidator: DomainValidator[smithy.WahaMessageTextRequest, WahaMessage] = { wahaMessageTextRequest =>
    val fields = List(
      wahaMessageTextRequest.payload.from,
      wahaMessageTextRequest.payload.data.info.sender,
      wahaMessageTextRequest.payload.data.info.senderAlt,
    )

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

    ZIO
      .foreach(validateWahaUserAccountIDCal.toOption.map(waha.WahaPhone.fromUserAccountID))(
        wahaPhoneNumberValidator.validate
      )
      .map(_.getOrElse(NonEmptyChain.one(InvalidFieldError("phoneNumber", "Empty user account id", "")).invalid))
      .map(validatedPhoneNumber =>
        (
          validateRequiredFields("userID", fields, waha.UserID.either),
          validateRequiredField("messageID", wahaMessageTextRequest.payload.id, waha.MessageID.either),
          validateWahaChatIDCal,
          validateWahaUserAccountIDCal,
          validateWahaWhatsAppPhoneNumberCal,
          validateRequiredField("fullName", wahaMessageTextRequest.payload.data.info.pushName, waha.FullName.either),
          validateRequiredField("messageText", wahaMessageTextRequest.payload.body, waha.MessageText.either),
          validatedPhoneNumber.map(_.phoneNumberE164),
        ).mapN(WahaMessage.apply)
      )
  }
}

object WahaServiceValidator {

  val live = ZLayer.derive[WahaServiceValidator]
}
