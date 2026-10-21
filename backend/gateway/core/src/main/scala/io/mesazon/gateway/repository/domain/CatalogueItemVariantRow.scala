package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemVariantRow(
    organizationID: OrganizationID,
    catalogueItemVariantID: CatalogueItemVariantID,
    catalogueItemID: CatalogueItemID,
    sku: Option[CatalogueItemVariantSku],
    label: Option[CatalogueItemVariantLabel],
    description: Option[CatalogueItemVariantDescription],
    attributes: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
    price: Option[CatalogueItemVariantPrice],
    sellingUnit: CatalogueItemVariantSaleUnit,
    status: CatalogueItemVariantStatus,
    isArchived: Boolean,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
