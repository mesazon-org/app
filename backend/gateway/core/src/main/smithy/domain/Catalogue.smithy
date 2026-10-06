$version: "2.0"

namespace io.mesazon.gateway.smithy

use alloy#UUID

enum CatalogueItemStatus {
    Active
    Archived
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

structure CatalogueItemVariantPrice {
    @required
    amount: BigDecimal
    @required
    currency: String
}

structure CatalogueItemVariant {
    name: String
    sku: String
    @required
    saleUnit: String
    measureValue: BigDecimal
    measureUnit: String
    packageType: String
    packageCount: Integer
    price: CatalogueItemVariantPrice
}

structure CatalogueItemVariantDetails {
    @required
    catalogueItemVariantID: UUID
    name: String
    sku: String
    @required
    saleUnit: String
    measureValue: BigDecimal
    measureUnit: String
    packageType: String
    packageCount: Integer
    price: CatalogueItemVariantPrice
}

structure InsertCatalogueItemVariantPostRequest {
    @required
    catalogueItemID: UUID
    @required
    catalogueItemVariant: CatalogueItemVariant
}

structure UpdateCatalogueItemVariantPutRequest {
    @required
    catalogueItemVariantID: UUID
    name: String
    sku: String
    saleUnit: String
    measureValue: BigDecimal
    measureUnit: String
    packageType: String
    packageCount: Integer
    price: CatalogueItemVariantPrice
}

structure ArchiveCatalogueItemVariantPutRequest {
    @required
    catalogueItemVariantID: UUID
}

structure GetCatalogueItemVariantGetResponse {
    @required
    catalogueItemVariantID: UUID
    @required
    catalogueItemID: UUID
    name: String
    sku: String
    @required
    saleUnit: String
    measureValue: BigDecimal
    measureUnit: String
    packageType: String
    packageCount: Integer
    price: CatalogueItemVariantPrice
}
