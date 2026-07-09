$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#UUID
use alloy#simpleRestJson

/// **Required Onboard Stage:** COMPLETED
@simpleRestJson
@completedOnboardStage
@httpBearerAuth
service CustomerBookService {
    version: "1.0.0",
    operations: [GetCustomerGet, GetCustomersGet, InsertCustomersPost, UpdateCustomersPut, DeleteCustomersPost]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/customer/{customerID}", code: 200)
operation GetCustomerGet {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpLabel
        customerID: UUID
    }
    output: GetCustomerGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/customers", code: 200)
operation GetCustomersGet {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
    }
    output: GetCustomersGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/customers", code: 204)
operation InsertCustomersPost {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: InsertCustomersPostRequest
    }
    errors: [BadRequest, Unauthorized, Forbidden, ValidationError, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/update/customers", code: 204)
operation UpdateCustomersPut {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: UpdateCustomersPutRequest
    }
    errors: [BadRequest, Unauthorized, Forbidden, ValidationError, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/delete/customers", code: 204)
operation DeleteCustomersPost {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: DeleteCustomersPostRequest
    }
    errors: [BadRequest, Unauthorized, Forbidden, ValidationError, InternalServerError]
}
