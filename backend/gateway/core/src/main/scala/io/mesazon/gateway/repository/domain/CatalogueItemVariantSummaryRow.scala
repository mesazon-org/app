package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemVariantSummaryRow(
    catalogueItemVariantID: CatalogueItemVariantID,
    sku: Option[CatalogueItemVariantSku],
    label: Option[CatalogueItemVariantLabel],
    price: Option[CatalogueItemVariantPrice],
    sellingUnit: CatalogueItemVariantSaleUnit,
    status: CatalogueItemVariantStatus,
)
