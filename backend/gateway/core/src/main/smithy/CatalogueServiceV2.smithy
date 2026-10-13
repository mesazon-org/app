$version: "2"

// Temporary staging service; renamed to CatalogueService in io.mesazon.gateway.smithy at cutover. Not wired.
namespace io.mesazon.gateway.smithy.v2

use alloy#UUID
use alloy#simpleRestJson
use io.mesazon.gateway.smithy#BadRequest
use io.mesazon.gateway.smithy#Conflict
use io.mesazon.gateway.smithy#Forbidden
use io.mesazon.gateway.smithy#InternalServerError
use io.mesazon.gateway.smithy#OrganizationScopedInput
use io.mesazon.gateway.smithy#Unauthorized
use io.mesazon.gateway.smithy#ValidationError
use io.mesazon.gateway.smithy#completedOnboardStage
use io.mesazon.gateway.smithy#organizationUserRolesAllowed

/// **Required Onboard Stage:** COMPLETED
@simpleRestJson
@completedOnboardStage
@httpBearerAuth
service CatalogueServiceV2 {
    version: "1.0.0",
    operations: [
        InsertCatalogueItemPost
        InsertCatalogueItemsPost

        UpdateCatalogueItemPut

        ArchiveCatalogueItemPut
        ArchiveCatalogueItemVariantPut

        GetCatalogueItemGet
        GetCatalogueItemsGet
        GetCatalogueItemsByIDsGet
    ]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/insert/catalogue-item", code: 200)
operation InsertCatalogueItemPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCatalogueItemPostRequest
    }
    output: InsertCatalogueItemPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/insert/catalogue-items", code: 200)
operation InsertCatalogueItemsPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: InsertCatalogueItemsPostRequest
    }
    output: InsertCatalogueItemsPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/update/catalogue-item", code: 204)
operation UpdateCatalogueItemPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: UpdateCatalogueItemPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/archive/catalogue-item", code: 204)
operation ArchiveCatalogueItemPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: ArchiveCatalogueItemPutRequest
    }
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/archive/catalogue-item-variant", code: 204)
operation ArchiveCatalogueItemVariantPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: ArchiveCatalogueItemVariantPutRequest
    }
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
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

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/get/catalogue-items", code: 200)
operation GetCatalogueItemsGet {
    input := with [OrganizationScopedInput] {}
    output: GetCatalogueItemsGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/get/catalogue-items-by-ids", code: 200)
operation GetCatalogueItemsByIDsGet {
    input := with [OrganizationScopedInput] {
        @default([])
        @httpQuery("catalogueItemIDs")
        catalogueItemIDs: CatalogueItemIDs
    }
    output: GetCatalogueItemsByIDsGetResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, InternalServerError]
}
