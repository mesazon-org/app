package io.mesazon.gateway.repository.domain

import zio.NonEmptyChunk

case class CatalogueItemWithVariantsRow(
    catalogueItemRowV2: CatalogueItemRowV2,
    catalogueItemVariantRows: NonEmptyChunk[CatalogueItemVariantRow],
)
