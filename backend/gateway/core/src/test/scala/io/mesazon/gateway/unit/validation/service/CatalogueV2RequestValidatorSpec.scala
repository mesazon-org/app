package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.CatalogueV2SmithyArbitraries
import io.mesazon.gateway.validation.domain.PriceValidator
import io.mesazon.gateway.validation.service.CatalogueV2RequestValidator
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import smithy4s.Nullable
import zio.*

class CatalogueV2RequestValidatorSpec extends ZWordSpecBase, CatalogueV2SmithyArbitraries {

  private val invalidTextMessage =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val validator = ZIO
    .service[CatalogueV2RequestValidator]
    .provide(
      CatalogueV2RequestValidator.live,
      PriceValidator.live,
    )
    .zioValue

  private def nestedError(fieldName: String, index: Int, errors: InvalidFieldError*): InvalidFieldError =
    InvalidFieldError(
      fieldName,
      s"Failed with invalid fields ${errors.toList.mkString("[", ", ", "]")}",
      Seq.empty,
      index,
    )

  private def validatedSaleUnit(
      requestSmithy: smithy.v2.CatalogueItemPostRequest,
      saleUnitSmithy: smithy.v2.CatalogueItemVariantSaleUnit,
  ): CatalogueItemVariantSaleUnit =
    validator
      .validatedCatalogueItemPostRequest(
        requestSmithy.copy(variants = List(requestSmithy.variants.head.copy(sellingUnit = saleUnitSmithy)))
      )
      .zioValue
      .variants
      .head
      .sellingUnit

  "CatalogueV2RequestValidator" should {
    "validatedCatalogueItemPostRequest" should {
      "return a validated item with its variants" in {
        val request = arbitrarySample[CatalogueItemPostRequest]

        validator
          .validatedCatalogueItemPostRequest(request.transformInto[smithy.v2.CatalogueItemPostRequest])
          .zioValue shouldBe request
      }

      "preserve every quantity unit with optional selling-unit parts absent" in {
        val requestSmithy = arbitrarySample[smithy.v2.CatalogueItemPostRequest]
        val unitsSmithy   = smithy.v2.CatalogueItemVariantUnit.values
        val saleUnits     = unitsSmithy.map(unitSmithy =>
          validatedSaleUnit(
            requestSmithy,
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(1), unitSmithy),
              None,
              None,
            ),
          )
        )

        saleUnits.map(_.quantity.unit.toString) shouldBe unitsSmithy.map(_.stringValue)
        saleUnits.map(_.quantity.unit).toSet shouldBe CatalogueItemVariantUnit.values.toSet
        saleUnits.forall(saleUnit => saleUnit.perUnit.isEmpty && saleUnit.unitSize.isEmpty) shouldBe true
      }

      "map every permitted per-unit and unit-size enum case" in {
        val requestSmithy = arbitrarySample[smithy.v2.CatalogueItemPostRequest]
        val perUnits      = smithy.v2.CatalogueItemVariantUnitPerUnit.values
        val unitSizes     = smithy.v2.CatalogueItemVariantUnitUnitSize.values

        val perUnitResults = perUnits.map(unit =>
          validatedSaleUnit(
            requestSmithy,
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(1), smithy.v2.CatalogueItemVariantUnit.Pack),
              Some(smithy.v2.CatalogueItemVariantPerUnit(BigDecimal(2), unit)),
              None,
            ),
          ).perUnit.value.unit
        )
        val unitSizeResults = unitSizes.map(unit =>
          validatedSaleUnit(
            requestSmithy,
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(1), smithy.v2.CatalogueItemVariantUnit.Piece),
              None,
              Some(smithy.v2.CatalogueItemVariantUnitSize(BigDecimal(3), unit)),
            ),
          ).unitSize.value.unit
        )

        perUnitResults shouldBe CatalogueItemVariantUnit.PerUnit.values.toList
        unitSizeResults shouldBe CatalogueItemVariantUnit.UnitSize.values.toList
      }

      "preserve selling-unit bounds and every supplied decimal place" in {
        val requestSmithy = arbitrarySample[smithy.v2.CatalogueItemPostRequest]
        val smallest      = BigDecimal("0.00000000000000000001")
        val largest       = BigDecimal("99999.0000")
        val result        = validatedSaleUnit(
          requestSmithy,
          smithy.v2.CatalogueItemVariantSaleUnit(
            smithy.v2.CatalogueItemVariantQuantity(smallest, smithy.v2.CatalogueItemVariantUnit.Piece),
            Some(smithy.v2.CatalogueItemVariantPerUnit(largest, smithy.v2.CatalogueItemVariantUnitPerUnit.Bottle)),
            Some(smithy.v2.CatalogueItemVariantUnitSize(smallest, smithy.v2.CatalogueItemVariantUnitUnitSize.Litre)),
          ),
        )

        result.quantity.value.value.scale shouldBe smallest.scale
        result.perUnit.value.value.value.scale shouldBe largest.scale
        result.unitSize.value.value.value.scale shouldBe smallest.scale
        result.quantity.value.value shouldBe smallest
        result.perUnit.value.value.value shouldBe largest
        result.unitSize.value.value.value shouldBe smallest
      }

      "fail with a ValidationError for invalid selling-unit values in field order" in {
        val requestSmithy  = arbitrarySample[smithy.v2.CatalogueItemPostRequest]
        val quantity       = BigDecimal(0)
        val perUnit        = BigDecimal("-0.1")
        val unitSize       = BigDecimal("99999.0001")
        val saleUnitSmithy = smithy.v2.CatalogueItemVariantSaleUnit(
          smithy.v2.CatalogueItemVariantQuantity(quantity, smithy.v2.CatalogueItemVariantUnit.Box),
          Some(smithy.v2.CatalogueItemVariantPerUnit(perUnit, smithy.v2.CatalogueItemVariantUnitPerUnit.Bag)),
          Some(smithy.v2.CatalogueItemVariantUnitSize(unitSize, smithy.v2.CatalogueItemVariantUnitUnitSize.Gram)),
        )

        validator
          .validatedCatalogueItemPostRequest(
            requestSmithy.copy(variants = List(requestSmithy.variants.head.copy(sellingUnit = saleUnitSmithy)))
          )
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            nestedError(
              "variant",
              0,
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
          )
        )
      }

      "fail with a ValidationError containing item and variant fields in declaration order" in {
        val invalidPriceAmount = BigDecimal("-1")
        val invalidPriceInner  = InvalidFieldError("amount", "Amount must be non-negative", List("-1"))
        val invalidPrice       = InvalidFieldError(
          "price",
          s"Failed with invalid fields [$invalidPriceInner]",
          List("-1", "EUR"),
        )
        val variant = arbitrarySample[smithy.v2.CatalogueItemVariantInsert].copy(
          sku = Some(""),
          label = Some(""),
          description = Some(""),
          attributes = Map("" -> ""),
          price = Some(smithy.v2.CatalogueItemVariantPrice(invalidPriceAmount, smithy.v2.Currency.EUR)),
          sellingUnit = smithy.v2.CatalogueItemVariantSaleUnit(
            smithy.v2.CatalogueItemVariantQuantity(BigDecimal(0), smithy.v2.CatalogueItemVariantUnit.Piece),
            Some(
              smithy.v2.CatalogueItemVariantPerUnit(
                BigDecimal(100000),
                smithy.v2.CatalogueItemVariantUnitPerUnit.Bag,
              )
            ),
            Some(
              smithy.v2.CatalogueItemVariantUnitSize(
                BigDecimal(0),
                smithy.v2.CatalogueItemVariantUnitUnitSize.Gram,
              )
            ),
          ),
        )
        val requestSmithy = smithy.v2.CatalogueItemPostRequest(
          name = "",
          description = Some(""),
          variants = List(variant),
        )

        validator.validatedCatalogueItemPostRequest(requestSmithy).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              InvalidFieldError("name", invalidTextMessage, List("")),
              InvalidFieldError("description", invalidTextMessage, List("")),
              nestedError(
                "variant",
                0,
                InvalidFieldError("sku", invalidTextMessage, List("")),
                InvalidFieldError("label", invalidTextMessage, List("")),
                InvalidFieldError("description", invalidTextMessage, List("")),
                InvalidFieldError("attributeName", invalidTextMessage, List("")),
                InvalidFieldError("attributeValue", invalidTextMessage, List("")),
                invalidPrice,
                InvalidFieldError(
                  "quantity",
                  CatalogueItemVariantQuantityValue.either(BigDecimal(0)).left.value,
                  List("0"),
                ),
                InvalidFieldError(
                  "perUnit",
                  CatalogueItemVariantPerUnitValue.either(BigDecimal(100000)).left.value,
                  List("100000"),
                ),
                InvalidFieldError(
                  "unitSize",
                  CatalogueItemVariantUnitSizeValue.either(BigDecimal(0)).left.value,
                  List("0"),
                ),
              ),
            )
          )
      }

      "fail with a ValidationError when an item has no variants" in {
        val request = arbitrarySample[smithy.v2.CatalogueItemPostRequest].copy(variants = Nil)

        validator.validatedCatalogueItemPostRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(InvalidFieldError("variant", "At least one variant must be provided", Seq.empty))
          )
      }
    }

    "validatedCatalogueItemBatchPostRequest" should {
      "return validated items" in {
        val request = arbitrarySample[CatalogueItemBatchPostRequest]

        validator
          .validatedCatalogueItemBatchPostRequest(request.transformInto[smithy.v2.CatalogueItemBatchPostRequest])
          .zioValue shouldBe request
      }

      "fail with a ValidationError when the batch has no catalogue items" in {
        validator
          .validatedCatalogueItemBatchPostRequest(smithy.v2.CatalogueItemBatchPostRequest(Nil))
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(InvalidFieldError("catalogueItem", "At least one catalogueItem must be provided", Seq.empty))
          )
      }

      "fail with a ValidationError preserving item and variant indexes" in {
        val validItem      = arbitrarySample[smithy.v2.CatalogueItemPostRequest]
        val invalidName    = validItem.copy(name = "")
        val invalidVariant = validItem.copy(variants = List(validItem.variants.head.copy(sku = Some(""))))
        val request        = smithy.v2.CatalogueItemBatchPostRequest(
          List(validItem, invalidName, validItem, invalidVariant)
        )

        validator.validatedCatalogueItemBatchPostRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              nestedError("catalogueItem", 1, InvalidFieldError("name", invalidTextMessage, List(""))),
              nestedError(
                "catalogueItem",
                3,
                nestedError("variant", 0, InvalidFieldError("sku", invalidTextMessage, List(""))),
              ),
            )
          )
      }
    }

    "validatedCatalogueItemPutRequest" should {
      "return a validated update with an empty insert list" in {
        val request = arbitrarySample[CatalogueItemPutRequest].copy(variantsInsert = Nil)

        validator
          .validatedCatalogueItemPutRequest(request.transformInto[smithy.v2.CatalogueItemPutRequest])
          .zioValue shouldBe request
      }

      "preserve nullable keep, clear, and replace values" in {
        val update = arbitrarySample[CatalogueItemVariantUpdate].copy(
          sku = None,
          label = Some(None),
          description = Some(Some(CatalogueItemVariantDescription.assume("replacement"))),
          price = Some(None),
        )
        val request = smithy.v2.CatalogueItemPutRequest(
          name = None,
          description = Some(Nullable.Null),
          variantsUpdate = List(update.transformInto[smithy.v2.CatalogueItemVariantUpdate]),
          variantsInsert = Nil,
        )

        val validated = validator.validatedCatalogueItemPutRequest(request).zioValue

        validated.description shouldBe Some(None)
        validated.variantsUpdate.head.sku shouldBe None
        validated.variantsUpdate.head.label shouldBe Some(None)
        validated.variantsUpdate.head.description shouldBe update.description
        validated.variantsUpdate.head.price shouldBe Some(None)
      }

      "accept every enabled variant status" in {
        val variant = arbitrarySample[smithy.v2.CatalogueItemVariantUpdate]

        CatalogueItemVariantStatus.enabled.foreach { status =>
          val request = smithy.v2.CatalogueItemPutRequest(
            name = None,
            description = None,
            variantsUpdate =
              List(variant.copy(status = Some(status.transformInto[smithy.v2.CatalogueItemVariantStatus]))),
            variantsInsert = Nil,
          )

          validator.validatedCatalogueItemPutRequest(request).zioValue.variantsUpdate.head.status shouldBe Some(status)
        }
      }

      "fail with a ValidationError containing update fields and a disabled variant status" in {
        val variant = arbitrarySample[smithy.v2.CatalogueItemVariantUpdate].copy(
          sku = Some(Nullable.Value("")),
          label = Some(Nullable.Value("")),
          description = Some(Nullable.Value("")),
          attributes = Some(Map("" -> "")),
          price = Some(Nullable.Value(smithy.v2.CatalogueItemVariantPrice(BigDecimal("-1"), smithy.v2.Currency.USD))),
          sellingUnit = Some(
            smithy.v2.CatalogueItemVariantSaleUnit(
              smithy.v2.CatalogueItemVariantQuantity(BigDecimal(0), smithy.v2.CatalogueItemVariantUnit.Box),
              None,
              None,
            )
          ),
          status = Some(smithy.v2.CatalogueItemVariantStatus.Archived),
        )
        val request = smithy.v2.CatalogueItemPutRequest(
          name = Some(""),
          description = Some(Nullable.Value("")),
          variantsUpdate = List(variant),
          variantsInsert = List(arbitrarySample[smithy.v2.CatalogueItemVariantInsert].copy(sku = Some(""))),
        )
        val invalidPriceInner = InvalidFieldError("amount", "Amount must be non-negative", List("-1"))

        validator.validatedCatalogueItemPutRequest(request).zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              InvalidFieldError("name", invalidTextMessage, List("")),
              InvalidFieldError("description", invalidTextMessage, List("")),
              nestedError(
                "variant",
                0,
                InvalidFieldError("sku", invalidTextMessage, List("")),
                InvalidFieldError("label", invalidTextMessage, List("")),
                InvalidFieldError("description", invalidTextMessage, List("")),
                InvalidFieldError("attributeName", invalidTextMessage, List("")),
                InvalidFieldError("attributeValue", invalidTextMessage, List("")),
                InvalidFieldError("price", s"Failed with invalid fields [$invalidPriceInner]", List("-1", "USD")),
                InvalidFieldError(
                  "quantity",
                  CatalogueItemVariantQuantityValue.either(BigDecimal(0)).left.value,
                  List("0"),
                ),
                InvalidFieldError(
                  "status",
                  "Disabled status is not allowed for catalogue item variant updates",
                  List("Archived"),
                ),
              ),
              nestedError("variant", 0, InvalidFieldError("sku", invalidTextMessage, List(""))),
            )
          )
      }

      "fail with a ValidationError for every disabled variant status" in {
        val variant = arbitrarySample[smithy.v2.CatalogueItemVariantUpdate]

        CatalogueItemVariantStatus.disabled.foreach { status =>
          val request = smithy.v2.CatalogueItemPutRequest(
            name = None,
            description = None,
            variantsUpdate =
              List(variant.copy(status = Some(status.transformInto[smithy.v2.CatalogueItemVariantStatus]))),
            variantsInsert = Nil,
          )

          validator.validatedCatalogueItemPutRequest(request).zioError shouldBe
            ServiceError.BadRequestError.ValidationError(
              List(
                nestedError(
                  "variant",
                  0,
                  InvalidFieldError(
                    "status",
                    "Disabled status is not allowed for catalogue item variant updates",
                    List(status.toString),
                  ),
                )
              )
            )
        }
      }
    }

    "validatedCatalogueItemUniquenessGetRequest" should {
      "return a validated name or SKU uniqueness request" in {
        val request = arbitrarySample[CatalogueItemUniquenessGetRequest]
        val input   = smithy.v2.CatalogueItemUniquenessGetInput(
          organizationID = arbitrarySample[OrganizationID].value,
          name = request.name.map(_.value),
          sku = request.sku.map(_.value),
        )

        validator.validatedCatalogueItemUniquenessGetRequest(input).zioValue shouldBe request
      }

      "fail with a ValidationError for invalid supplied name and SKU" in {
        val organizationID = arbitrarySample[OrganizationID].value

        validator
          .validatedCatalogueItemUniquenessGetRequest(
            smithy.v2.CatalogueItemUniquenessGetInput(organizationID, Some(""), Some(""))
          )
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", invalidTextMessage, List("")),
            InvalidFieldError("sku", invalidTextMessage, List("")),
          )
        )
      }

      "fail with a ValidationError naming both absent uniqueness fields" in {
        val organizationID = arbitrarySample[OrganizationID].value

        validator
          .validatedCatalogueItemUniquenessGetRequest(
            smithy.v2.CatalogueItemUniquenessGetInput(organizationID, None, None)
          )
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            InvalidFieldError("name", "At least one of name or sku must be provided", Seq.empty),
            InvalidFieldError("sku", "At least one of name or sku must be provided", Seq.empty),
          )
        )
      }
    }
  }
}
