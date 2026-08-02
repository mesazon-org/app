package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemSummaryRow(
    catalogueItemID: CatalogueItemID,
    name: CatalogueItemName,
    image: Option[CatalogueItemImage],
    status: CatalogueItemStatus,
)
