package io.mesazon.domain.gateway

case class InsertCatalogueItemPostRequest(
    name: CatalogueItemName,
    unit: CatalogueItemUnit,
    price: Option[CatalogueItemPrice],
)

case class InsertCatalogueItemsPostRequest(
    catalogueItems: List[InsertCatalogueItemPostRequest]
)

case class UpdateCatalogueItemPutRequest(
    catalogueItemID: CatalogueItemID,
    name: Option[CatalogueItemName],
    unit: Option[CatalogueItemUnit],
    price: Option[CatalogueItemPrice],
)
