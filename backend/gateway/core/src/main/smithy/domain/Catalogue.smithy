$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

enum CatalogueItemStatus {
    ACTIVE
    ARCHIVED
}

structure CatalogueItemPriceRequest {
    @required
    amount: BigDecimal
    @required
    currency: String
}

structure InsertCatalogueItemPostRequest {
    @required
    name: String
    @required
    unit: String
    price: CatalogueItemPriceRequest
}

list InsertCatalogueItems {
    member: InsertCatalogueItemPostRequest
}

structure InsertCatalogueItemsPostRequest {
    @default([])
    catalogueItems: InsertCatalogueItems
}

structure UpdateCatalogueItemPutRequest {
    @required
    catalogueItemID: UUID
    name: String
    unit: String
    price: CatalogueItemPriceRequest
}

structure ArchiveCatalogueItemPutRequest {
    @required
    catalogueItemID: UUID
}

structure GetCatalogueItemGetResponse {
    @required
    catalogueItemID: UUID
    @required
    name: String
    @required
    unit: String
    price: CatalogueItemPriceRequest
    imageNormalizedUrl: String
}

structure GetCatalogueItem {
    @required
    catalogueItemID: UUID
    @required
    name: String
    @required
    status: CatalogueItemStatus
    imageNormalizedUrl: String
}

list GetCatalogueItems {
    member: GetCatalogueItem
}

structure GetCatalogueItemsGetResponse {
    @required
    catalogueItems: GetCatalogueItems
}
