package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemRowV2(
    organizationID: OrganizationID,
    catalogueItemID: CatalogueItemID,
    name: CatalogueItemName,
    description: Option[CatalogueItemDescription],
    imageAsset: Option[CatalogueItemImageAsset],
    status: CatalogueItemStatus,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
