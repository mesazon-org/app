package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class CatalogueItemRow(
    organizationID: OrganizationID,
    catalogueItemID: CatalogueItemID,
    name: CatalogueItemName,
    unit: CatalogueItemUnit,
    priceAmount: Option[CatalogueItemPriceAmount],
    priceCurrency: Option[CatalogueItemPriceCurrency],
    photoOriginalBucketKey: Option[OrganizationLogoOriginalBucketKey],
    photoNormalizedBucketKey: Option[OrganizationLogoNormalizedBucketKey],
    photoOriginalFileName: Option[OrganizationLogoOriginalFileName],
    status: CatalogueItemStatus,
    createdAt: CreatedAt,
    updatedAt: UpdatedAt,
)
