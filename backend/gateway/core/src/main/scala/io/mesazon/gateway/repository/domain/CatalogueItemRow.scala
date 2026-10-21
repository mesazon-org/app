package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemRow(
    organizationID: OrganizationID,
    catalogueItemID: CatalogueItemID,
    name: CatalogueItemName,
    description: Option[CatalogueItemDescription],
    imageAsset: Option[CatalogueItemImageAsset],
    status: CatalogueItemStatus,
    isArchived: Boolean,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
