package io.mesazon.gateway.validation.domain

import cats.data.ValidatedNec
import cats.syntax.all.*
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.service.validateRequiredField
import io.scalaland.chimney.dsl.*
import zio.*

final class CatalogueItemVariantSaleUnitValidator {

  def validate(
      catalogueItemVariantSaleUnitSmithy: smithy.v2.CatalogueItemVariantSaleUnit
  ): UIO[ValidatedNec[InvalidFieldError, CatalogueItemVariantSaleUnit]] =
    ZIO.succeed {
      (
        validateRequiredField(
          "quantity",
          catalogueItemVariantSaleUnitSmithy.quantity.value,
          CatalogueItemVariantQuantityValue.either,
        ).map(value =>
          CatalogueItemVariantQuantity(
            value,
            catalogueItemVariantSaleUnitSmithy.quantity.unit.transformInto[CatalogueItemVariantUnit],
          )
        ),
        catalogueItemVariantSaleUnitSmithy.perUnit.traverse(perUnitSmithy =>
          validateRequiredField(
            "perUnit",
            perUnitSmithy.value,
            CatalogueItemVariantPerUnitValue.either,
          ).map(value =>
            CatalogueItemVariantPerUnit(
              value,
              perUnitSmithy.unit.transformInto[CatalogueItemVariantUnit.PerUnit],
            )
          )
        ),
        catalogueItemVariantSaleUnitSmithy.unitSize.traverse(unitSizeSmithy =>
          validateRequiredField(
            "unitSize",
            unitSizeSmithy.value,
            CatalogueItemVariantUnitSizeValue.either,
          ).map(value =>
            CatalogueItemVariantUnitSize(
              value,
              unitSizeSmithy.unit.transformInto[CatalogueItemVariantUnit.UnitSize],
            )
          )
        ),
      ).mapN(CatalogueItemVariantSaleUnit.apply)
    }
}

object CatalogueItemVariantSaleUnitValidator {

  val live = ZLayer.derive[CatalogueItemVariantSaleUnitValidator]
}
