$version: "2"

namespace io.mesazon.gateway.smithy

use alloy#UUID
use alloy#simpleRestJson

/// **Required Onboard Stage:** COMPLETED
@simpleRestJson
@completedOnboardStage
@httpBearerAuth
service CatalogueService {
    version: "1.0.0",
    operations: [
        InsertCatalogueItemPost
        InsertCatalogueItemsPost

        UpdateCatalogueItemPut

        ArchiveCatalogueItemPut

        GetCatalogueItemGet
        GetCatalogueItemsGet
    ]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/catalogue-item", code: 204)
operation InsertCatalogueItemPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCatalogueItemPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "POST", uri: "/insert/catalogue-items", code: 204)
operation InsertCatalogueItemsPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCatalogueItemsPostRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/update/catalogue-item", code: 204)
operation UpdateCatalogueItemPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: UpdateCatalogueItemPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN"])
@http(method: "PUT", uri: "/archive/catalogue-item", code: 204)
operation ArchiveCatalogueItemPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: ArchiveCatalogueItemPutRequest
    }
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/catalogue-item/{catalogueItemID}", code: 200)
operation GetCatalogueItemGet {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        catalogueItemID: UUID
    }
    output: GetCatalogueItemGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`OWNER`, `ADMIN`, `USER`]
@organizationUserRolesAllowed(roles: ["OWNER", "ADMIN", "USER"])
@http(method: "GET", uri: "/get/catalogue-items", code: 200)
operation GetCatalogueItemsGet {
    input := with [OrganizationScopedInput] {}
    output: GetCatalogueItemsGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}
