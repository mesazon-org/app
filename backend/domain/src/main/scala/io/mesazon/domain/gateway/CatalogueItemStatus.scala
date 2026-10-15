package io.mesazon.domain.gateway

enum CatalogueItemStatus {
  case Active
  case Archived
}

object CatalogueItemStatus {
  val enabled: List[CatalogueItemStatus]  = List(Active)
  val disabled: List[CatalogueItemStatus] = List(Archived)
}
