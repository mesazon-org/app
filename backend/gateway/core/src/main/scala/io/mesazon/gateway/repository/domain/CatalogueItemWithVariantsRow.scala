package io.mesazon.gateway.repository.domain

import zio.NonEmptyChunk

case class CatalogueItemWithVariantsRow(
    catalogueItemRow: CatalogueItemRow,
    catalogueItemVariantRows: NonEmptyChunk[CatalogueItemVariantRow],
)
