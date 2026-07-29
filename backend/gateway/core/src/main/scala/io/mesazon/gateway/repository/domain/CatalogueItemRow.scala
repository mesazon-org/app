package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemRow(
    organizationID: OrganizationID,
    catalogueItemID: CatalogueItemID,
    name: CatalogueItemName,
    unit: CatalogueItemUnit,
    price: Option[CatalogueItemPrice],
    photo: Option[CatalogueItemPhoto],
    status: CatalogueItemStatus,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
