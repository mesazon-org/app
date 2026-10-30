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
    operations: [
        CustomerBookIndividualPost
        CustomerBookBusinessPost
        CustomerBookBatchPost

        CustomerBookIndividualPut

        CustomerBookBusinessPut

        CustomerBookArchivePut

        CustomerBookIndividualGet
        CustomerBookBusinessGet

        CustomerBookSummaryGet
        CustomerBookUniquenessGet
    ]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/customer-book/individual", code: 200)
operation CustomerBookIndividualPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: CustomerBookIndividualPostRequest
    }
    output: CustomerBookIndividualPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/customer-book/business", code: 200)
operation CustomerBookBusinessPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: CustomerBookBusinessPostRequest
    }
    output: CustomerBookBusinessPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/customer-book/batch", code: 200)
operation CustomerBookBatchPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: CustomerBookBatchPostRequest
    }
    output: CustomerBookBatchPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/customer-book/individual/{customerID}", code: 204)
operation CustomerBookIndividualPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID

        @required
        @httpPayload
        request: CustomerBookIndividualPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/customer-book/business/{customerID}", code: 204)
operation CustomerBookBusinessPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID

        @required
        @httpPayload
        request: CustomerBookBusinessPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/customer-book/{customerID}/archive", code: 204)
operation CustomerBookArchivePut {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID
    }
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/customer-book/individual/{customerID}", code: 200)
operation CustomerBookIndividualGet {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID
    }
    output: CustomerBookIndividualGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/customer-book/business/{customerID}", code: 200)
operation CustomerBookBusinessGet {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID
    }
    output: CustomerBookBusinessGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/customer-book/summary", code: 200)
operation CustomerBookSummaryGet {
    input := with [OrganizationScopedInput] {
        @default([])
        @httpQuery("customerIDs")
        customerIDs: CustomerIDs
    }
    output: CustomerBookSummaryGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/customer-book/uniqueness", code: 200)
operation CustomerBookUniquenessGet {
    input := with [OrganizationScopedInput] {
        @httpQuery("name")
        name: String
        @httpQuery("customerType")
        customerType: CustomerType
        @httpQuery("customerID")
        customerID: UUID
    }
    output: CustomerBookUniquenessGetResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, InternalServerError]
}
