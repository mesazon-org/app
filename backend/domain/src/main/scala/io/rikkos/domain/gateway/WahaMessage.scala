package io.rikkos.domain.gateway

import io.rikkos.domain.waha

case class WahaMessage(
    wahaUserID: waha.UserID,
    wahaMessageID: waha.MessageID,
    wahaChatID: waha.ChatID,
    wahaUserAccountID: waha.UserAccountID,
    wahaWhatsAppPhoneNumber: waha.WhatsAppPhoneNumber,
    wahaFullName: waha.FullName,
    wahaMessageText: waha.MessageText,
    phoneNumber: PhoneNumberE164,
)
