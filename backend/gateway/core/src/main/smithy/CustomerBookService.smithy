$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#UUID
use alloy#simpleRestJson

/// # Global Requirements
/// **Required Onboard Stage:** **COMPLETED**
///
/// The customer book lets organization users manage the customers of
/// their organization: fetch a single customer or the whole book, and
/// insert, update or delete customers in batches. All endpoints require
/// a fully onboarded user that is assigned to the organization given in
/// the `X-Organization-ID` header. Viewing the book is allowed to every
/// organization member; inserting, updating and deleting customers
/// require the `OWNER` or `ADMIN` role.
@simpleRestJson
@completedOnboardStage
@httpBearerAuth
service CustomerBookService {
    version: "1.0.0",
    operations: [GetCustomerGet, GetCustomersGet, InsertCustomersPost, UpdateCustomersPut, DeleteCustomersPost]
}

/// **Allowed Organization Roles:** [`OWNER`, `ADMIN`, `USER`]
@http(method: "GET", uri: "/get/customer/{customerID}", code: 200)
@organizationRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
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
    errors: [Unauthorized, Forbidden, InternalServerError]
}

/// **Allowed Organization Roles:** [`OWNER`, `ADMIN`, `USER`]
@http(method: "GET", uri: "/get/customers", code: 200)
@organizationRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
operation GetCustomersGet {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
    }
    output: GetCustomersGetResponse
    errors: [Unauthorized, Forbidden, InternalServerError]
}

/// **Allowed Organization Roles:** [`OWNER`, `ADMIN`]
@http(method: "POST", uri: "/insert/customers", code: 204)
@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])
operation InsertCustomersPost {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: InsertCustomersPostRequest
    }
    errors: [Unauthorized, Forbidden, ValidationError, InternalServerError]
}

/// **Allowed Organization Roles:** [`OWNER`, `ADMIN`]
@http(method: "PUT", uri: "/update/customers", code: 204)
@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])
operation UpdateCustomersPut {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: UpdateCustomersPutRequest
    }
    errors: [Unauthorized, Forbidden, ValidationError, InternalServerError]
}

/// **Allowed Organization Roles:** [`OWNER`, `ADMIN`]
@http(method: "POST", uri: "/delete/customers", code: 204)
@organizationRolesAllowed(roles: ["OWNER", "ADMIN"])
operation DeleteCustomersPost {
    input := {
        @required
        @httpHeader("X-Organization-ID")
        organizationID: UUID
        @required
        @httpPayload
        request: DeleteCustomersPostRequest
    }
    errors: [Unauthorized, Forbidden, ValidationError, InternalServerError]
}
