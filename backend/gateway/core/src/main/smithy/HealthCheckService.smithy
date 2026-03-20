$version: "2"
namespace io.mesazon.gateway.smithy

use alloy#simpleRestJson

@simpleRestJson
service HealthCheckService {
    version: "1.0.0",
    operations: [Liveness, Readiness]
}

@readonly
@http(method: "GET", uri: "/liveness", code: 204)
operation Liveness {}

@readonly
@http(method: "GET", uri: "/readiness", code: 204)
operation Readiness {
    errors: [ServiceUnavailable]
}
