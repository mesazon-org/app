$version: "2.0"

namespace io.mesazon.gateway.smithy

enum OnboardStage {
    EMAIL_VERIFICATION
    EMAIL_VERIFIED
    PASSWORD_PROVIDED
    PHONE_VERIFICATION
    PHONE_VERIFIED
}

structure PhoneNumberRequest {
    @required
    phoneNationalNumber: String
    @required
    phoneCountryCode: String
}