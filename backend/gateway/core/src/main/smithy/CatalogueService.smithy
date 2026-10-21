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
        CatalogueItemPost
        CatalogueItemBatchPost

        CatalogueItemPut

        CatalogueItemArchivePut
        CatalogueItemVariantArchivePut

        CatalogueItemGet
        CatalogueItemSummaryGet

        CatalogueItemUniquenessGet
    ]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/catalogue/item", code: 200)
operation CatalogueItemPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: CatalogueItemPostRequest
    }
    output: CatalogueItemPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "POST", uri: "/catalogue/item/batch", code: 200)
operation CatalogueItemBatchPost {
    input := with [OrganizationScopedInput] {
        @required
        @httpPayload
        request: CatalogueItemBatchPostRequest
    }
    output: CatalogueItemBatchPostResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/catalogue/item/{catalogueItemID}", code: 204)
operation CatalogueItemPut {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        catalogueItemID: UUID

        @required
        @httpPayload
        request: CatalogueItemPutRequest
    }
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, Conflict, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/catalogue/item/{catalogueItemID}/archive", code: 204)
operation CatalogueItemArchivePut {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        catalogueItemID: UUID
    }
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin"])
@http(method: "PUT", uri: "/catalogue/item/{catalogueItemID}/variant/{catalogueItemVariantID}/archive", code: 204)
operation CatalogueItemVariantArchivePut {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        catalogueItemID: UUID

        @required
        @httpLabel
        catalogueItemVariantID: UUID
    }
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/catalogue/item/{catalogueItemID}", code: 200)
operation CatalogueItemGet {
    input := with [OrganizationScopedInput] {
        @required
        @httpLabel
        catalogueItemID: UUID
    }
    output: CatalogueItemGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/catalogue/item/summary", code: 200)
operation CatalogueItemSummaryGet {
    input := with [OrganizationScopedInput] {
        @default([])
        @httpQuery("catalogueItemIDs")
        catalogueItemIDs: CatalogueItemIDs
    }
    output: CatalogueItemSummaryGetResponse
    errors: [BadRequest, Unauthorized, Forbidden, InternalServerError]
}

/// **Required Organization User Roles:** [`Owner`, `Admin`, `User`]
@organizationUserRolesAllowed(roles: ["Owner", "Admin", "User"])
@http(method: "GET", uri: "/catalogue/item/uniqueness", code: 200)
operation CatalogueItemUniquenessGet {
    input := with [OrganizationScopedInput] {
        @httpQuery("name")
        name: String
        @httpQuery("sku")
        sku: String
    }
    output: CatalogueItemUniquenessGetResponse
    errors: [BadRequest, ValidationError, Unauthorized, Forbidden, InternalServerError]
}
