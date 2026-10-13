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

enum CatalogueItemVariantUnitSize {
    Gram
    Kilogram
    Millilitre
    Litre
    Millimetre
    Centimetre
    Metre
    SquareMetre
}

enum CatalogueItemVariantPerUnit {
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
    unit: CatalogueItemVariantPerUnit
}

structure CatalogueItemVariantUnitSizeRequest {
    @required
    value: BigDecimal
    @required
    unit: CatalogueItemVariantUnitSize
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

structure InsertCatalogueItemPostRequest {
    @required
    name: String
    description: String
    @default([])
    variants: InsertCatalogueItemVariants
}

list InsertCatalogueItems {
    member: InsertCatalogueItemPostRequest
}

structure InsertCatalogueItemsPostRequest {
    @default([])
    catalogueItems: InsertCatalogueItems
}

list CatalogueItemIDs {
    member: UUID
}

structure InsertCatalogueItemPostResponse {
    @required
    catalogueItemID: UUID
}

structure InsertCatalogueItemsPostResponse {
    @required
    catalogueItemIDs: CatalogueItemIDs
}

structure UpdateCatalogueItemVariant {
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

structure UpdateCatalogueItemPutRequest {
    @required
    catalogueItemID: UUID
    name: String
    @nullable
    description: String
    @default([])
    variants: UpdateCatalogueItemVariants
}

structure ArchiveCatalogueItemPutRequest {
    @required
    catalogueItemID: UUID
}

structure ArchiveCatalogueItemVariantPutRequest {
    @required
    catalogueItemID: UUID
    @required
    catalogueItemVariantID: UUID
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

structure GetCatalogueItemGetResponse {
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

list GetCatalogueItemFromPrices {
    member: CatalogueItemVariantPriceRequest
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
    fromPrices: GetCatalogueItemFromPrices
    @required
    variantCount: Integer
}

list GetCatalogueItems {
    member: GetCatalogueItem
}

structure GetCatalogueItemsGetResponse {
    @required
    catalogueItems: GetCatalogueItems
}

list GetCatalogueItemByIDsFromPrices {
    member: CatalogueItemVariantPriceRequest
}

structure GetCatalogueItemByIDs {
    @required
    catalogueItemID: UUID
    @required
    name: String
    @required
    status: CatalogueItemStatus
    imageNormalizedUrl: String
    @required
    fromPrices: GetCatalogueItemByIDsFromPrices
    @required
    variantCount: Integer
}

list GetCatalogueItemsByIDs {
    member: GetCatalogueItemByIDs
}

structure GetCatalogueItemsByIDsGetResponse {
    @required
    catalogueItems: GetCatalogueItemsByIDs
}
