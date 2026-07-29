package io.mesazon.gateway.unit.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.CatalogueSmithyArbitraries
import io.mesazon.gateway.validation.domain.PriceDomainValidator
import io.mesazon.gateway.validation.service.CatalogueRequestValidator
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import zio.*

class CatalogueRequestValidatorSpec extends ZWordSpecBase, CatalogueSmithyArbitraries {

  private val invalidFieldErrorMessageNonEmptyTrimmed =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val catalogueRequestValidator = ZIO
    .service[CatalogueRequestValidator]
    .provide(CatalogueRequestValidator.live, PriceDomainValidator.live)
    .zioValue

  "CatalogueRequestValidator" should {
    "validatedInsertCatalogueItemPostRequest" should {
      "validate a valid catalogue item round-trip" in {
        val insertCatalogueItemPostRequest = arbitrarySample[InsertCatalogueItemPostRequest]
        catalogueRequestValidator
          .validatedInsertCatalogueItemPostRequest(
            insertCatalogueItemPostRequest.transformInto[smithy.InsertCatalogueItemPostRequest]
          )
          .zioValue shouldBe insertCatalogueItemPostRequest
      }

      "validate an item without a price" in {
        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(
          price = None
        )

        catalogueRequestValidator
          .validatedInsertCatalogueItemPostRequest(insertCatalogueItemPostRequestSmithy)
          .zioValue
          .price shouldBe None
      }

      "fail with a ValidationError accumulating name, unit, and price errors" in {
        val insertCatalogueItemPostRequestSmithy = arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(
          name = "",
          unit = "",
          price = Some(smithy.CatalogueItemPriceRequest(BigDecimal(-1), "bad")),
        )
        val invalidFieldErrorAmount =
          InvalidFieldError("amount", "Amount must be non-negative", List("-1"))
        val invalidFieldErrorCurrency =
          InvalidFieldError("currency", "Unsupported ISO currency: [bad]", List("bad"))

        catalogueRequestValidator
          .validatedInsertCatalogueItemPostRequest(insertCatalogueItemPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("name", invalidFieldErrorMessageNonEmptyTrimmed, List("")),
              InvalidFieldError("unit", invalidFieldErrorMessageNonEmptyTrimmed, List("")),
              InvalidFieldError(
                "price",
                s"Failed with invalid fields [$invalidFieldErrorAmount, $invalidFieldErrorCurrency]",
                List("-1", "bad"),
              ),
            )
          )
      }
    }

    "validatedInsertCatalogueItemsPostRequest" should {
      "validate a valid batch round-trip" in {
        val insertCatalogueItemsPostRequest = arbitrarySample[InsertCatalogueItemsPostRequest]
        catalogueRequestValidator
          .validatedInsertCatalogueItemsPostRequest(
            insertCatalogueItemsPostRequest.transformInto[smithy.InsertCatalogueItemsPostRequest]
          )
          .zioValue shouldBe insertCatalogueItemsPostRequest
      }

      "validate an empty batch" in {
        val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(Nil)

        catalogueRequestValidator
          .validatedInsertCatalogueItemsPostRequest(insertCatalogueItemsPostRequestSmithy)
          .zioValue shouldBe InsertCatalogueItemsPostRequest(Nil)
      }

      "fail with a ValidationError for the first, middle, and last invalid catalogue items while accepting the second and fourth" in {
        val insertCatalogueItemPostRequestSmithy =
          arbitrarySample[smithy.InsertCatalogueItemPostRequest]
        val insertCatalogueItemPostRequestSmithy1 =
          insertCatalogueItemPostRequestSmithy.copy(name = "", price = None)
        val insertCatalogueItemPostRequestSmithy2 =
          insertCatalogueItemPostRequestSmithy
        val insertCatalogueItemPostRequestSmithy3 =
          insertCatalogueItemPostRequestSmithy.copy(unit = "", price = None)
        val insertCatalogueItemPostRequestSmithy4 =
          insertCatalogueItemPostRequestSmithy
        val insertCatalogueItemPostRequestSmithy5 =
          insertCatalogueItemPostRequestSmithy.copy(
            price = Some(smithy.CatalogueItemPriceRequest(BigDecimal(-1), "bad"))
          )
        val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(
          List(
            insertCatalogueItemPostRequestSmithy1,
            insertCatalogueItemPostRequestSmithy2,
            insertCatalogueItemPostRequestSmithy3,
            insertCatalogueItemPostRequestSmithy4,
            insertCatalogueItemPostRequestSmithy5,
          )
        )
        val invalidFieldErrorName =
          InvalidFieldError("name", invalidFieldErrorMessageNonEmptyTrimmed, List(""))
        val invalidFieldErrorUnit =
          InvalidFieldError("unit", invalidFieldErrorMessageNonEmptyTrimmed, List(""))
        val invalidFieldErrorAmount =
          InvalidFieldError("amount", "Amount must be non-negative", List("-1"))
        val invalidFieldErrorCurrency =
          InvalidFieldError("currency", "Unsupported ISO currency: [bad]", List("bad"))
        val invalidFieldErrorPrice = InvalidFieldError(
          "price",
          s"Failed with invalid fields [$invalidFieldErrorAmount, $invalidFieldErrorCurrency]",
          List("-1", "bad"),
        )

        catalogueRequestValidator
          .validatedInsertCatalogueItemsPostRequest(insertCatalogueItemsPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError(
                "catalogueItem",
                s"Failed with invalid fields [$invalidFieldErrorName]",
                List(),
                index = 0,
              ),
              InvalidFieldError(
                "catalogueItem",
                s"Failed with invalid fields [$invalidFieldErrorUnit]",
                List(),
                index = 2,
              ),
              InvalidFieldError(
                "catalogueItem",
                s"Failed with invalid fields [$invalidFieldErrorPrice]",
                List(),
                index = 4,
              ),
            )
          )
      }
    }

    "validatedUpdateCatalogueItemPutRequest" should {
      "validate a valid update round-trip" in {
        val updateCatalogueItemPutRequest = arbitrarySample[UpdateCatalogueItemPutRequest]
        catalogueRequestValidator
          .validatedUpdateCatalogueItemPutRequest(
            updateCatalogueItemPutRequest.transformInto[smithy.UpdateCatalogueItemPutRequest]
          )
          .zioValue shouldBe updateCatalogueItemPutRequest
      }

      "validate an update with omitted optional fields" in {
        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest].copy(
          name = None,
          unit = None,
          price = None,
        )

        catalogueRequestValidator
          .validatedUpdateCatalogueItemPutRequest(updateCatalogueItemPutRequestSmithy)
          .zioValue shouldBe
          UpdateCatalogueItemPutRequest(
            catalogueItemID = CatalogueItemID(updateCatalogueItemPutRequestSmithy.catalogueItemID),
            name = None,
            unit = None,
            price = None,
          )
      }

      "fail with a ValidationError accumulating optional field and price boundary errors" in {
        val updateCatalogueItemPutRequestSmithy = arbitrarySample[smithy.UpdateCatalogueItemPutRequest].copy(
          name = Some(""),
          unit = Some(""),
          price = Some(smithy.CatalogueItemPriceRequest(BigDecimal("1.001"), "USD")),
        )
        val invalidFieldErrorAmount =
          InvalidFieldError("amount", "Amount scale [3] exceeds currency fraction digits [2]", List("1.001"))

        catalogueRequestValidator
          .validatedUpdateCatalogueItemPutRequest(updateCatalogueItemPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            invalidFields = List(
              InvalidFieldError("name", invalidFieldErrorMessageNonEmptyTrimmed, List("")),
              InvalidFieldError("unit", invalidFieldErrorMessageNonEmptyTrimmed, List("")),
              InvalidFieldError(
                "price",
                s"Failed with invalid fields [$invalidFieldErrorAmount]",
                List("1.001", "USD"),
              ),
            )
          )
      }
    }
  }
}
