package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*
import zio.NonEmptyChunk

case class CatalogueItemSummaryRow(
    catalogueItemID: CatalogueItemID,
    name: CatalogueItemName,
    imageAsset: Option[CatalogueItemImageAsset],
    status: CatalogueItemStatus,
    catalogueItemVariantSummaryRows: NonEmptyChunk[CatalogueItemVariantSummaryRow],
)
