$version: "2.0"

// Temporary staging namespace; moved to io.mesazon.gateway.smithy at cutover, replacing domain/Catalogue.smithy.
namespace io.mesazon.gateway.smithy.v2

use alloy#UUID
use alloy#nullable
use io.mesazon.gateway.smithy#CatalogueItemStatus

enum CatalogueItemVariantStatus {
    Active
    Inactive
    OutOfStock
    Archived
}

enum CatalogueItemVariantUnit {
    Gram
    Kilogram
    Millilitre
    Litre
    Millimetre
    Centimetre
    Metre
    SquareMetre
    Pack
    Box
    Bag
    Bottle
    Can
    Jar
    Piece
}

enum CatalogueItemVariantUnitUnitSize {
    Gram
    Kilogram
    Millilitre
    Litre
    Millimetre
    Centimetre
    Metre
    SquareMetre
}

enum CatalogueItemVariantUnitPerUnit {
    Bag
    Bottle
    Can
    Jar
    Piece
}

structure CatalogueItemVariantPriceRequest {
    @required
    amount: BigDecimal
    @required
    currency: String
}

structure CatalogueItemVariantQuantityRequest {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnit
}

structure CatalogueItemVariantPerUnitRequest {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnitPerUnit
}

structure CatalogueItemVariantUnitSizeRequest {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnitUnitSize
}

structure CatalogueItemVariantSaleUnitRequest {
    @required
    quantity: CatalogueItemVariantQuantityRequest
    perUnit: CatalogueItemVariantPerUnitRequest
    unitSize: CatalogueItemVariantUnitSizeRequest
}

map CatalogueItemVariantAttributes {
    key: String
    value: String
}

structure InsertCatalogueItemVariant {
    sku: String
    label: String
    description: String
    @default({})
    attributes: CatalogueItemVariantAttributes
    price: CatalogueItemVariantPriceRequest
    @required
    sellingUnit: CatalogueItemVariantSaleUnitRequest
}

list InsertCatalogueItemVariants {
    member: InsertCatalogueItemVariant
}

structure CatalogueItemPostRequest {
    @required
    name: String
    description: String
    @default([])
    variants: InsertCatalogueItemVariants
}

list InsertCatalogueItems {
    member: CatalogueItemPostRequest
}

structure CatalogueItemBatchPostRequest {
    @default([])
    catalogueItems: InsertCatalogueItems
}

list CatalogueItemIDs {
    member: UUID
}

structure CatalogueItemPostResponse {
    @required
    catalogueItemID: UUID
}

structure CatalogueItemBatchPostResponse {
    @required
    catalogueItemIDs: CatalogueItemIDs
}

structure UpdateCatalogueItemVariant {
    @required
    catalogueItemVariantID: UUID
    @nullable
    sku: String
    @nullable
    label: String
    @nullable
    description: String
    attributes: CatalogueItemVariantAttributes
    @nullable
    price: CatalogueItemVariantPriceRequest
    sellingUnit: CatalogueItemVariantSaleUnitRequest
    status: CatalogueItemVariantStatus
}

list UpdateCatalogueItemVariants {
    member: UpdateCatalogueItemVariant
}

structure CatalogueItemPutRequest {
    name: String
    @nullable
    description: String
    @default([])
    updateVariants: UpdateCatalogueItemVariants
    @default([])
    insertVariants: InsertCatalogueItemVariants
}

structure GetCatalogueItemVariant {
    @required
    catalogueItemVariantID: UUID
    sku: String
    label: String
    description: String
    @required
    attributes: CatalogueItemVariantAttributes
    price: CatalogueItemVariantPriceRequest
    @required
    sellingUnit: CatalogueItemVariantSaleUnitRequest
    @required
    status: CatalogueItemVariantStatus
}

list GetCatalogueItemVariants {
    member: GetCatalogueItemVariant
}

structure CatalogueItemGetResponse {
    @required
    catalogueItemID: UUID
    @required
    name: String
    description: String
    @required
    status: CatalogueItemStatus
    imageNormalizedUrl: String
    @required
    variants: GetCatalogueItemVariants
}

structure GetCatalogueItemVariantSummary {
    @required
    catalogueItemVariantID: UUID
    sku: String
    label: String
    price: CatalogueItemVariantPriceRequest
    @required
    sellingUnit: CatalogueItemVariantSaleUnitRequest
    @required
    status: CatalogueItemVariantStatus
}

list GetCatalogueItemVariantSummaries {
    member: GetCatalogueItemVariantSummary
}

structure GetCatalogueItem {
    @required
    catalogueItemID: UUID
    @required
    name: String
    @required
    status: CatalogueItemStatus
    imageNormalizedUrl: String
    @required
    variants: GetCatalogueItemVariantSummaries
}

list GetCatalogueItems {
    member: GetCatalogueItem
}

structure CatalogueItemSummaryGetResponse {
    @required
    catalogueItems: GetCatalogueItems
}

structure CatalogueItemUniquenessGetResponse {
    isNameUnique: Boolean
    isSkuUnique: Boolean
}
