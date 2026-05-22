package io.mesazon.domain.gateway

case class PhoneNumber(
    phoneRegion: PhoneRegion,
    phoneCountryCode: PhoneCountryCode,
    phoneNationalNumber: PhoneNationalNumber,
    phoneNumberE164: PhoneNumberE164,
)
