package io.mesazon.gateway.unit.validation.domain

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.validation.domain.CatalogueItemVariantSaleUnitValidator
import io.mesazon.testkit.base.ZWordSpecBase

class CatalogueItemVariantSaleUnitValidatorSpec extends ZWordSpecBase {

  private val validator = new CatalogueItemVariantSaleUnitValidator

  "CatalogueItemVariantSaleUnitValidator" should {
    "accept every quantity unit and leave optional parts absent" in {
      val unitsSmithy = smithy.v2.CatalogueItemVariantUnit.values
      val saleUnits   = unitsSmithy.map(unitSmithy =>
        validator
          .validate(
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(1), unitSmithy),
              None,
              None,
            )
          )
          .zioValue
          .toOption
          .value
      )

      saleUnits.map(_.quantity.unit.toString) shouldBe unitsSmithy.map(_.stringValue)
      saleUnits.map(_.quantity.unit).toSet shouldBe CatalogueItemVariantUnit.values.toSet
      saleUnits.forall(saleUnit => saleUnit.perUnit.isEmpty && saleUnit.unitSize.isEmpty) shouldBe true
    }

    "map every permitted per-unit and unit-size enum case" in {
      val perUnits  = smithy.v2.CatalogueItemVariantUnitPerUnit.values
      val unitSizes = smithy.v2.CatalogueItemVariantUnitUnitSize.values

      val perUnitResults = perUnits.map(unit =>
        validator
          .validate(
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(1), smithy.v2.CatalogueItemVariantUnit.Pack),
              Some(smithy.v2.CatalogueItemVariantPerUnit(BigDecimal(2), unit)),
              None,
            )
          )
          .zioValue
          .toOption
          .value
          .perUnit
          .value
          .unit
      )
      val unitSizeResults = unitSizes.map(unit =>
        validator
          .validate(
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(1), smithy.v2.CatalogueItemVariantUnit.Piece),
              None,
              Some(smithy.v2.CatalogueItemVariantUnitSize(BigDecimal(3), unit)),
            )
          )
          .zioValue
          .toOption
          .value
          .unitSize
          .value
          .unit
      )

      perUnitResults shouldBe CatalogueItemVariantUnit.PerUnit.values.toList
      unitSizeResults shouldBe CatalogueItemVariantUnit.UnitSize.values.toList
    }

    "accept the product bounds and preserve every supplied decimal place" in {
      val smallest = BigDecimal("0.00000000000000000001")
      val largest  = BigDecimal("99999.0000")
      val result   = validator
        .validate(
          smithy.v2.CatalogueItemVariantSaleUnit(
            smithy.v2.CatalogueItemVariantQuantity(smallest, smithy.v2.CatalogueItemVariantUnit.Piece),
            Some(
              smithy.v2.CatalogueItemVariantPerUnit(
                largest,
                smithy.v2.CatalogueItemVariantUnitPerUnit.Bottle,
              )
            ),
            Some(
              smithy.v2.CatalogueItemVariantUnitSize(
                smallest,
                smithy.v2.CatalogueItemVariantUnitUnitSize.Litre,
              )
            ),
          )
        )
        .zioValue
        .toOption
        .value

      result.quantity.value.value.scale shouldBe smallest.scale
      result.perUnit.value.value.value.scale shouldBe largest.scale
      result.unitSize.value.value.value.scale shouldBe smallest.scale
      result.quantity.value.value shouldBe smallest
      result.perUnit.value.value.value shouldBe largest
      result.unitSize.value.value.value shouldBe smallest
    }

    "accumulate invalid quantity, per-unit, and unit-size values in field order" in {
      val quantity = BigDecimal(0)
      val perUnit  = BigDecimal("-0.1")
      val unitSize = BigDecimal("99999.0001")

      validator
        .validate(
          smithy.v2.CatalogueItemVariantSaleUnit(
            smithy.v2.CatalogueItemVariantQuantity(quantity, smithy.v2.CatalogueItemVariantUnit.Box),
            Some(
              smithy.v2.CatalogueItemVariantPerUnit(
                perUnit,
                smithy.v2.CatalogueItemVariantUnitPerUnit.Bag,
              )
            ),
            Some(
              smithy.v2.CatalogueItemVariantUnitSize(
                unitSize,
                smithy.v2.CatalogueItemVariantUnitUnitSize.Gram,
              )
            ),
          )
        )
        .zioValue
        .toEither
        .left
        .toOption
        .value
        .toNonEmptyList
        .toList shouldBe List(
        InvalidFieldError(
          "quantity",
          CatalogueItemVariantQuantityValue.either(quantity).left.value,
          List(quantity.toString),
        ),
        InvalidFieldError(
          "perUnit",
          CatalogueItemVariantPerUnitValue.either(perUnit).left.value,
          List(perUnit.toString),
        ),
        InvalidFieldError(
          "unitSize",
          CatalogueItemVariantUnitSizeValue.either(unitSize).left.value,
          List(unitSize.toString),
        ),
      )
    }
  }
}
