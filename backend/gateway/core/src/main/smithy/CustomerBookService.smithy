$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#UUID
use alloy#simpleRestJson

/// # Global Requirements
/// **Required Onboard Stage:** **COMPLETED**
///
/// **Allowed Organization Roles:** [`OWNER`, `ADMIN`]
///
/// The customer book lets organization users manage the customers of
/// their organization in batches: insert new customers, update existing
/// ones and delete them. All endpoints require a fully onboarded user
/// that is assigned to the organization given in the `X-Organization-ID`
/// header as `OWNER` or `ADMIN`.
@simpleRestJson
@completedOnboardStage
@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])
@httpBearerAuth
service CustomerBookService {
    version: "1.0.0",
    operations: [GetCustomerGet, GetCustomersGet, InsertCustomersPost, UpdateCustomersPut, DeleteCustomersPost]
}

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
    errors: [Unauthorized, InternalServerError]
}

@http(method: "GET", uri: "/get/customers", code: 200)
operation GetCustomersGet {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
    }
    output: GetCustomersGetResponse
    errors: [Unauthorized, InternalServerError]
}

@http(method: "POST", uri: "/insert/customers", code: 200)
operation InsertCustomersPost {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: InsertCustomersPostRequest
    }
    errors: [Unauthorized, ValidationError, InternalServerError]
}

@http(method: "PUT", uri: "/update/customers", code: 200)
operation UpdateCustomersPut {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: UpdateCustomersPutRequest
    }
    errors: [Unauthorized, ValidationError, InternalServerError]
}

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
    errors: [Unauthorized, ValidationError, InternalServerError]
}
