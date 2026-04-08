$version: "2.0"

namespace io.mesazon.gateway.smithy

enum OnboardStage {
    EMAIL_VERIFICATION
    EMAIL_VERIFIED
    PASSWORD_PROVIDED
    DETAILS_PROVIDED
    PHONE_VERIFICATION
    PHONE_VERIFIED
}