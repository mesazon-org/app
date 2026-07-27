package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

// The three photo columns reuse the `OrganizationLogo*` bucket-key/file-name newtypes: they are
// structurally identical S3 key/filename strings and photo upload is deferred (see catalogue.md
// § Status). Revisit with dedicated `CataloguePhoto*` types when the upload transport lands.
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
