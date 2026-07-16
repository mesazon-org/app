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
        InsertCustomerIndividualPost
        InsertCustomerIndividualsPost

        InsertCustomerBusinessPost
        InsertCustomerBusinessesPost

        InsertCustomersPost

        UpdateCustomerIndividualPut

        UpdateCustomerBusinessPut

        AddCustomerBusinessContactsPut
        RemoveCustomerBusinessContactsPut

        GetCustomerIndividualGet
        GetCustomerBusinessGet

        GetCustomersGet
    ]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/customer-individual", code: 204)
operation InsertCustomerIndividualPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCustomerIndividualPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/customer-individuals", code: 204)
operation InsertCustomerIndividualsPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCustomerIndividualsPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/customer-business", code: 204)
operation InsertCustomerBusinessPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCustomerBusinessPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/customer-businesses", code: 204)
operation InsertCustomerBusinessesPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCustomerBusinessesPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/customers", code: 204)
operation InsertCustomersPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCustomersPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/update/customer-individual", code: 204)
operation UpdateCustomerIndividualPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: UpdateCustomerIndividualPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/update/customer-business", code: 204)
operation UpdateCustomerBusinessPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: UpdateCustomerBusinessPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/add/customer-business-contacts", code: 204)
operation AddCustomerBusinessContactsPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: AddCustomerBusinessContactsPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/remove/customer-business-contacts", code: 204)
operation RemoveCustomerBusinessContactsPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: RemoveCustomerBusinessContactsPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/customer-individual/{customerID}", code: 200)
operation GetCustomerIndividualGet {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID
    }
    output: GetCustomerIndividualGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/customer-business/{customerID}", code: 200)
operation GetCustomerBusinessGet {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        customerID: UUID
    }
    output: GetCustomerBusinessGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/customers", code: 200)
operation GetCustomersGet {
    input := with [OrganizationScopedInput] {}
    output: GetCustomersGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}
