$version: "2"

namespace io.rikkos.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service WahaService {
    version: "1.0.0",
    operations: [WahaWebhookMessage, WahaWebhookStartTyping, WahaWebhookStopTyping]
}

@http(method: "POST", uri: "/waha/webhook/message", code: 204)
operation WahaWebhookMessage {
    input := {
        @required
        @httpPayload
        request: WahaMessageText
    }
    errors: [BadRequest, InternalServerError]
}

@http(method: "POST", uri: "/waha/webhook/typing/start", code: 204)
operation WahaWebhookStartTyping {
    input := {
        @required
        @httpPayload
        request: WahaStartTyping
    }
    errors: [BadRequest, InternalServerError]
}

@http(method: "POST", uri: "/waha/webhook/typing/stop", code: 204)
operation WahaWebhookStopTyping {
    input := {
        @required
        @httpPayload
        request: WahaStopTyping
    }
    errors: [BadRequest, InternalServerError]
}

