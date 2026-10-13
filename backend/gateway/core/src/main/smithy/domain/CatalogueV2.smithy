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

structure CatalogueItemVariantPrice {
    @required
    amount: BigDecimal
    @required
    currency: String
}

structure CatalogueItemVariantQuantity {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnit
}

structure CatalogueItemVariantPerUnit {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnitPerUnit
}

structure CatalogueItemVariantUnitSize {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnitUnitSize
}

structure CatalogueItemVariantSaleUnit {
    @required
    quantity: CatalogueItemVariantQuantity
    perUnit: CatalogueItemVariantPerUnit
    unitSize: CatalogueItemVariantUnitSize
}

map CatalogueItemVariantAttributes {
    key: String
    value: String
}

list CatalogueItemIDs {
    member: UUID
}

structure CatalogueItemVariantInsert {
    sku: String
    label: String
    description: String
    @default({})
    attributes: CatalogueItemVariantAttributes
    price: CatalogueItemVariantPrice
    @required
    sellingUnit: CatalogueItemVariantSaleUnit
}

list CatalogueItemVariantsInsert {
    member: CatalogueItemVariantInsert
}

structure CatalogueItemVariantUpdate {
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
    price: CatalogueItemVariantPrice
    sellingUnit: CatalogueItemVariantSaleUnit
    status: CatalogueItemVariantStatus
}

list CatalogueItemVariantsUpdate {
    member: CatalogueItemVariantUpdate
}

structure CatalogueItemVariant {
    @required
    catalogueItemVariantID: UUID
    sku: String
    label: String
    description: String
    @required
    attributes: CatalogueItemVariantAttributes
    price: CatalogueItemVariantPrice
    @required
    sellingUnit: CatalogueItemVariantSaleUnit
    @required
    status: CatalogueItemVariantStatus
}

list CatalogueItemVariants {
    member: CatalogueItemVariant
}

structure CatalogueItemVariantSummary {
    @required
    catalogueItemVariantID: UUID
    sku: String
    label: String
    price: CatalogueItemVariantPrice
    @required
    sellingUnit: CatalogueItemVariantSaleUnit
    @required
    status: CatalogueItemVariantStatus
}

list CatalogueItemVariantSummaries {
    member: CatalogueItemVariantSummary
}

structure CatalogueItemSummary {
    @required
    catalogueItemID: UUID
    @required
    name: String
    @required
    status: CatalogueItemStatus
    imageNormalizedUrl: String
    @required
    variants: CatalogueItemVariantSummaries
}

list CatalogueItemSummaries {
    member: CatalogueItemSummary
}

structure CatalogueItemPostRequest {
    @required
    name: String
    description: String
    @default([])
    variants: CatalogueItemVariantsInsert
}

list CatalogueItemsInsert {
    member: CatalogueItemPostRequest
}

structure CatalogueItemPostResponse {
    @required
    catalogueItemID: UUID
}

structure CatalogueItemBatchPostRequest {
    @default([])
    catalogueItems: CatalogueItemsInsert
}

structure CatalogueItemBatchPostResponse {
    @required
    catalogueItemIDs: CatalogueItemIDs
}

structure CatalogueItemPutRequest {
    name: String
    @nullable
    description: String
    @default([])
    variantsUpdate: CatalogueItemVariantsUpdate
    @default([])
    variantsInsert: CatalogueItemVariantsInsert
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
    variants: CatalogueItemVariants
}

structure CatalogueItemSummaryGetResponse {
    @required
    catalogueItemSummaries: CatalogueItemSummaries
}

structure CatalogueItemUniquenessGetResponse {
    isNameUnique: Boolean
    isSkuUnique: Boolean
}
