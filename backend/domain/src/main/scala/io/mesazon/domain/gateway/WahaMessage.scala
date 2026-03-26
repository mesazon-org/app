package io.mesazon.domain.gateway

import io.mesazon.domain.waha

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
