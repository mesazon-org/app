package io.mesazon.domain.gateway

enum CatalogueItemVariantStatus {
  case Active
  case Inactive
  case OutOfStock
  case Archived
}

object CatalogueItemVariantStatus {
  val enabled: List[CatalogueItemVariantStatus]  = List(Active, Inactive, OutOfStock)
  val disabled: List[CatalogueItemVariantStatus] = List(Archived)
}
