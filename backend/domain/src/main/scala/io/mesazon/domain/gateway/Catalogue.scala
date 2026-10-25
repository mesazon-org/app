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

// AI

case class CatalogueItemVariantAttributesAIExtract(
    name: CatalogueItemVariantAttributeName,
    value: CatalogueItemVariantAttributeValue,
)

case class CatalogueItemVariantAIExtract(
    sku: Option[CatalogueItemVariantSku],
    label: Option[CatalogueItemVariantLabel],
    description: Option[CatalogueItemVariantDescription],
    attributes: List[CatalogueItemVariantAttributesAIExtract],
    price: Option[CatalogueItemVariantPrice],
    sellingUnit: Option[CatalogueItemVariantSaleUnit],
    missingRequiredSaleUnit: Boolean,
    skuIsDuplicate: Boolean,
    extractionNotes: List[String],
)

case class CatalogueItemAIExtract(
    name: CatalogueItemName,
    description: Option[CatalogueItemDescription],
    variants: List[CatalogueItemVariantAIExtract],
    atLeastOneVariant: Boolean,
    nameIsDuplicate: Boolean,
    extractionNotes: List[String],
)

case class CatalogueFileAIExtract(
    entriesIdentified: Long,
    entriesProcessed: Long,
    candidates: List[CatalogueItemAIExtract],
    emptyEntryRows: List[Long],
    unidentifiedEntryRows: List[Long],
    extractionNotes: List[String],
)

// Catalogue File

case class CatalogueItemCandidate(
    name: CatalogueItemName,
    description: Option[CatalogueItemDescription],
    variants: List[CatalogueItemVariantInsert],
    needsAttention: Boolean,
    extractionNotes: List[String],
)

case class CatalogueFileAiExtractPostResponse(
    candidates: List[CatalogueItemCandidate],
    infoNotes: List[String],
    extractionNotes: List[String],
)
