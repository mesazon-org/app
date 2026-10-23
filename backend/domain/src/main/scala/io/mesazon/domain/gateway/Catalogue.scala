package io.mesazon.domain.gateway

import zio.NonEmptyChunk

case class CatalogueItemVariantInsert(
    sku: Option[CatalogueItemVariantSku],
    label: Option[CatalogueItemVariantLabel],
    description: Option[CatalogueItemVariantDescription],
    attributes: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
    price: Option[CatalogueItemVariantPrice],
    sellingUnit: CatalogueItemVariantSaleUnit,
)

case class CatalogueItemVariantUpdate(
    catalogueItemVariantID: CatalogueItemVariantID,
    sku: Option[Option[CatalogueItemVariantSku]],
    label: Option[Option[CatalogueItemVariantLabel]],
    description: Option[Option[CatalogueItemVariantDescription]],
    attributes: Option[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]],
    price: Option[Option[CatalogueItemVariantPrice]],
    sellingUnit: Option[CatalogueItemVariantSaleUnit],
    status: Option[CatalogueItemVariantStatus],
)

case class CatalogueItemPostRequest(
    name: CatalogueItemName,
    description: Option[CatalogueItemDescription],
    variants: NonEmptyChunk[CatalogueItemVariantInsert],
)

case class CatalogueItemBatchPostRequest(
    catalogueItems: NonEmptyChunk[CatalogueItemPostRequest]
)

case class CatalogueItemPutRequest(
    name: Option[CatalogueItemName],
    description: Option[Option[CatalogueItemDescription]],
    status: Option[CatalogueItemStatus],
    variantsUpdate: List[CatalogueItemVariantUpdate],
    variantsInsert: List[CatalogueItemVariantInsert],
)

case class CatalogueItemUniquenessGetRequest(
    name: Option[CatalogueItemName],
    sku: Option[CatalogueItemVariantSku],
)

case class CatalogueItemVariantCandidate(
    sku: Option[CatalogueItemVariantSku],
    label: Option[CatalogueItemVariantLabel],
    description: Option[CatalogueItemVariantDescription],
    attributes: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
    price: Option[CatalogueItemVariantPrice],
    sellingUnit: Option[CatalogueItemVariantSaleUnit],
    missingRequiredSaleUnit: Boolean,
    skuIsDuplicate: Boolean,
    extractionNotes: List[String],
)

case class CatalogueItemCandidate(
    name: CatalogueItemName,
    description: Option[CatalogueItemDescription],
    variants: List[CatalogueItemVariantCandidate],
    atLeastOneVariant: Boolean,
    nameIsDuplicate: Boolean,
    extractionNotes: List[String],
)

case class CatalogueFileExtract(
    entriesIdentified: Long,
    entriesProcessed: Long,
    candidates: List[CatalogueItemCandidate],
    emptyEntryRows: List[Long],
    unidentifiedEntryRows: List[Long],
    extractionNotes: List[String],
)

case class CatalogueFileExtractPostResponse(
    candidates: List[CatalogueItemCandidate],
    needsAttention: Boolean,
    infoNotes: List[String],
    extractionNotes: List[String],
)
