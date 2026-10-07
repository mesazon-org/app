package io.mesazon.domain.gateway

case class CatalogueItemVariantSaleUnit(
    quantity: CatalogueItemVariantQuantity,
    perUnit: Option[CatalogueItemVariantPerUnit],
    unitSize: Option[CatalogueItemVariantUnitSize],
)

case class CatalogueItemVariantQuantity(
    value: CatalogueItemVariantQuantityValue,
    unit: CatalogueItemVariantUnit,
)

case class CatalogueItemVariantPerUnit(
    value: CatalogueItemVariantPerUnitValue,
    unit: CatalogueItemVariantUnit.PerUnit,
)

case class CatalogueItemVariantUnitSize(
    value: CatalogueItemVariantUnitSizeValue,
    unit: CatalogueItemVariantUnit.UnitSize,
)
