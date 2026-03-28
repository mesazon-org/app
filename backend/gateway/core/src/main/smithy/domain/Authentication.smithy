$version: "2.0"

namespace io.mesazon.gateway.smithy

structure SignUpEmailRequest {
    @required
    email: String
}

structure SignUpEmailResponse {
    @required
    otpID: String
}
