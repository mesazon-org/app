$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service WahaService {
    version: "1.0.0",
    operations: [WahaWebhookMessage]
}

@http(method: "POST", uri: "/waha/webhook/message", code: 204)
operation WahaWebhookMessage {
    input := {
        @required
        @httpPayload
        request: WahaMessageTextRequest
    }
    errors: [BadRequest, InternalServerError]
}
