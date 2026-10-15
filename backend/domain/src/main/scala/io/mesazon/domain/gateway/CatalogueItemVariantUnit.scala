package io.mesazon.domain.gateway

sealed trait CatalogueItemVariantUnit

object CatalogueItemVariantUnit {
  enum UnitSize extends CatalogueItemVariantUnit {
    case Gram
    case Kilogram
    case Millilitre
    case Litre
    case Millimetre
    case Centimetre
    case Metre
    case SquareMetre
  }

  enum PackageUnit extends CatalogueItemVariantUnit {
    case Pack
    case Box
  }

  enum PerUnit extends CatalogueItemVariantUnit {
    case Bag
    case Bottle
    case Can
    case Jar
    case Piece
  }

  val values: Array[CatalogueItemVariantUnit] = UnitSize.values ++ PackageUnit.values ++ PerUnit.values
}
