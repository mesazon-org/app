package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.to
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.CatalogueRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{CatalogueService, ServiceTask}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.gateway.utils.{CatalogueSmithyArbitraries, RepositoryArbitraries}
import io.mesazon.gateway.validation.domain.PriceValidator
import io.mesazon.gateway.validation.service.CatalogueRequestValidator
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import zio.*

class CatalogueServiceSpec extends ZWordSpecBase, CatalogueSmithyArbitraries, RepositoryArbitraries {

  private val repositoryError =
    ServiceError.InternalServerError.RepositoryError("repository failure", new RuntimeException("boom"))

  private val invalidTextMessage =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private def nestedError(fieldName: String, index: Int, errors: ServiceError.BadRequestError.InvalidFieldError*) =
    ServiceError.BadRequestError.InvalidFieldError(
      fieldName,
      s"Failed with invalid fields ${errors.toList.mkString("[", ", ", "]")}",
      Seq.empty,
      index,
    )

  private def toInsertCatalogueItemInput(
      catalogueItemPostRequest: CatalogueItemPostRequest
  ): InsertCatalogueItemInput =
    InsertCatalogueItemInput(
      name = catalogueItemPostRequest.name,
      description = catalogueItemPostRequest.description,
      variants = catalogueItemPostRequest.variants.map(catalogueItemVariantInsert =>
        InsertCatalogueItemVariantInput(
          sku = catalogueItemVariantInsert.sku,
          label = catalogueItemVariantInsert.label,
          description = catalogueItemVariantInsert.description,
          attributes = catalogueItemVariantInsert.attributes,
          price = catalogueItemVariantInsert.price,
          sellingUnit = catalogueItemVariantInsert.sellingUnit,
          status = CatalogueItemVariantStatus.Active,
        )
      ),
    )

  private def toUpdateCatalogueItemVariantInput(
      catalogueItemVariantUpdate: CatalogueItemVariantUpdate
  ): UpdateCatalogueItemVariantInput =
    UpdateCatalogueItemVariantInput(
      catalogueItemVariantID = catalogueItemVariantUpdate.catalogueItemVariantID,
      sku = catalogueItemVariantUpdate.sku,
      label = catalogueItemVariantUpdate.label,
      description = catalogueItemVariantUpdate.description,
      attributes = catalogueItemVariantUpdate.attributes,
      price = catalogueItemVariantUpdate.price,
      sellingUnit = catalogueItemVariantUpdate.sellingUnit,
      status = catalogueItemVariantUpdate.status,
    )

  private def toInsertCatalogueItemVariantInput(
      catalogueItemVariantInsert: CatalogueItemVariantInsert
  ): InsertCatalogueItemVariantInput =
    InsertCatalogueItemVariantInput(
      sku = catalogueItemVariantInsert.sku,
      label = catalogueItemVariantInsert.label,
      description = catalogueItemVariantInsert.description,
      attributes = catalogueItemVariantInsert.attributes,
      price = catalogueItemVariantInsert.price,
      sellingUnit = catalogueItemVariantInsert.sellingUnit,
      status = CatalogueItemVariantStatus.Active,
    )

  private def toCatalogueItemVariantPriceResponse(
      catalogueItemVariantPrice: CatalogueItemVariantPrice
  ): smithy.CatalogueItemVariantPrice =
    smithy.CatalogueItemVariantPrice(
      amount = catalogueItemVariantPrice.value.amount.value,
      currency = catalogueItemVariantPrice.value.currency.transformInto[smithy.Currency],
    )

  private def toCatalogueItemVariantSaleUnitResponse(
      catalogueItemVariantSaleUnit: CatalogueItemVariantSaleUnit
  ): smithy.CatalogueItemVariantSaleUnit =
    smithy.CatalogueItemVariantSaleUnit(
      quantity = smithy.CatalogueItemVariantQuantity(
        value = catalogueItemVariantSaleUnit.quantity.value.value,
        unit = catalogueItemVariantSaleUnit.quantity.unit.transformInto[smithy.CatalogueItemVariantUnit],
      ),
      perUnit = catalogueItemVariantSaleUnit.perUnit.map(catalogueItemVariantPerUnit =>
        smithy.CatalogueItemVariantPerUnit(
          value = catalogueItemVariantPerUnit.value.value,
          unit = catalogueItemVariantPerUnit.unit.transformInto[smithy.CatalogueItemVariantUnitPerUnit],
        )
      ),
      unitSize = catalogueItemVariantSaleUnit.unitSize.map(catalogueItemVariantUnitSize =>
        smithy.CatalogueItemVariantUnitSize(
          value = catalogueItemVariantUnitSize.value.value,
          unit = catalogueItemVariantUnitSize.unit.transformInto[smithy.CatalogueItemVariantUnitUnitSize],
        )
      ),
    )

  private def toCatalogueItemVariantResponse(
      catalogueItemVariantRow: CatalogueItemVariantRow
  ): smithy.CatalogueItemVariant =
    smithy.CatalogueItemVariant(
      catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID.value,
      attributes = catalogueItemVariantRow.attributes.view.map((name, value) => name.value -> value.value).toMap,
      sellingUnit = toCatalogueItemVariantSaleUnitResponse(catalogueItemVariantRow.sellingUnit),
      status = catalogueItemVariantRow.status.transformInto[smithy.CatalogueItemVariantStatus],
      sku = catalogueItemVariantRow.sku.map(_.value),
      label = catalogueItemVariantRow.label.map(_.value),
      description = catalogueItemVariantRow.description.map(_.value),
      price = catalogueItemVariantRow.price.map(toCatalogueItemVariantPriceResponse),
    )

  private def toCatalogueItemSummaryVariantResponse(
      catalogueItemVariantSummaryRow: CatalogueItemVariantSummaryRow
  ): smithy.CatalogueItemVariantSummary =
    smithy.CatalogueItemVariantSummary(
      catalogueItemVariantID = catalogueItemVariantSummaryRow.catalogueItemVariantID.value,
      sellingUnit = toCatalogueItemVariantSaleUnitResponse(catalogueItemVariantSummaryRow.sellingUnit),
      status = catalogueItemVariantSummaryRow.status.transformInto[smithy.CatalogueItemVariantStatus],
      sku = catalogueItemVariantSummaryRow.sku.map(_.value),
      label = catalogueItemVariantSummaryRow.label.map(_.value),
      price = catalogueItemVariantSummaryRow.price.map(toCatalogueItemVariantPriceResponse),
    )

  private def toCatalogueItemGetResponse(
      catalogueItemWithVariantsRow: CatalogueItemWithVariantsRow,
      imageNormalizedUrlOpt: Option[S3MediaUrl],
  ): smithy.CatalogueItemGetResponse =
    smithy.CatalogueItemGetResponse(
      catalogueItemID = catalogueItemWithVariantsRow.catalogueItemRow.catalogueItemID.value,
      name = catalogueItemWithVariantsRow.catalogueItemRow.name.value,
      description = catalogueItemWithVariantsRow.catalogueItemRow.description.map(_.value),
      status = catalogueItemWithVariantsRow.catalogueItemRow.status.transformInto[smithy.CatalogueItemStatus],
      imageNormalizedUrl = imageNormalizedUrlOpt.map(_.value),
      variants = catalogueItemWithVariantsRow.catalogueItemVariantRows.toList.map(toCatalogueItemVariantResponse),
    )

  private def toCatalogueItemSummaryResponse(
      catalogueItemSummaryRow: CatalogueItemSummaryRow,
      imageNormalizedUrlOpt: Option[S3MediaUrl],
  ): smithy.CatalogueItemSummary =
    smithy.CatalogueItemSummary(
      catalogueItemID = catalogueItemSummaryRow.catalogueItemID.value,
      name = catalogueItemSummaryRow.name.value,
      status = catalogueItemSummaryRow.status.transformInto[smithy.CatalogueItemStatus],
      imageNormalizedUrl = imageNormalizedUrlOpt.map(_.value),
      variants = catalogueItemSummaryRow.catalogueItemVariantSummaryRows.toList.map(
        toCatalogueItemSummaryVariantResponse
      ),
    )

  "CatalogueService" when {
    "catalogueItemPost" should {
      "return the created catalogue item ID" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemPostRequest       = arbitrarySample[CatalogueItemPostRequest]
        val catalogueItemPostRequestSmithy =
          catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.insertCatalogueItem
          .expects(organizationID, toInsertCatalogueItemInput(catalogueItemPostRequest))
          .returningZIO(catalogueItemID)
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPost(organizationID.value, catalogueItemPostRequestSmithy)
          .zioValue shouldBe smithy.CatalogueItemPostResponse(catalogueItemID.value)
      }

      "fail with a ValidationError before repository access when the catalogue item request is invalid" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemPostRequestSmithy =
          arbitrarySample[smithy.CatalogueItemPostRequest].copy(name = "")

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPost(organizationID.value, catalogueItemPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              ServiceError.BadRequestError.InvalidFieldError(
                "name",
                invalidTextMessage,
                List(""),
              )
            )
          )
      }

      "fail with a RepositoryError when the repository rejects the insert" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemPostRequest       = arbitrarySample[CatalogueItemPostRequest]
        val catalogueItemPostRequestSmithy =
          catalogueItemPostRequest.transformInto[smithy.CatalogueItemPostRequest]

        catalogueRepositoryMock.insertCatalogueItem
          .expects(organizationID, toInsertCatalogueItemInput(catalogueItemPostRequest))
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPost(organizationID.value, catalogueItemPostRequestSmithy)
          .zioError shouldBe repositoryError
      }
    }

    "catalogueItemBatchPost" should {
      "return IDs in repository order for a non-empty batch" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemID1 = arbitrarySample[CatalogueItemID]
        val catalogueItemID2 = arbitrarySample[CatalogueItemID]

        catalogueItemID1 should not be catalogueItemID2

        val catalogueItemPostRequest1 = arbitrarySample[CatalogueItemPostRequest].copy(
          name = CatalogueItemName.assume("first catalogue item")
        )
        val catalogueItemPostRequest2 = arbitrarySample[CatalogueItemPostRequest].copy(
          name = CatalogueItemName.assume("second catalogue item")
        )
        val catalogueItemBatchPostRequestSmithy = smithy.CatalogueItemBatchPostRequest(
          List(
            catalogueItemPostRequest1.transformInto[smithy.CatalogueItemPostRequest],
            catalogueItemPostRequest2.transformInto[smithy.CatalogueItemPostRequest],
          )
        )

        catalogueRepositoryMock.insertCatalogueItems
          .expects(
            organizationID,
            NonEmptyChunk.fromIterable(
              toInsertCatalogueItemInput(catalogueItemPostRequest1),
              List(toInsertCatalogueItemInput(catalogueItemPostRequest2)),
            ),
          )
          .returningZIO(NonEmptyChunk.fromIterable(catalogueItemID1, List(catalogueItemID2)))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemBatchPost(organizationID.value, catalogueItemBatchPostRequestSmithy)
          .zioValue shouldBe smithy.CatalogueItemBatchPostResponse(
          List(catalogueItemID1.value, catalogueItemID2.value)
        )
      }

      "fail with a ValidationError before repository access when the batch is empty" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemBatchPost(organizationID.value, smithy.CatalogueItemBatchPostRequest(Nil))
          .zioError shouldBe ServiceError.BadRequestError.ValidationError(
          List(
            ServiceError.BadRequestError.InvalidFieldError(
              "catalogueItem",
              "At least one catalogueItem must be provided",
              Seq.empty,
            )
          )
        )
      }

      "fail with a ValidationError before making a batch write when one item is invalid" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val catalogueItemPostRequestValidSmithy   = arbitrarySample[smithy.CatalogueItemPostRequest]
        val catalogueItemPostRequestInvalidSmithy = catalogueItemPostRequestValidSmithy.copy(name = "")
        val catalogueItemBatchPostRequestSmithy   = smithy.CatalogueItemBatchPostRequest(
          List(catalogueItemPostRequestValidSmithy, catalogueItemPostRequestInvalidSmithy)
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemBatchPost(organizationID.value, catalogueItemBatchPostRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              nestedError(
                "catalogueItem",
                1,
                ServiceError.BadRequestError.InvalidFieldError("name", invalidTextMessage, List("")),
              )
            )
          )
      }

      "fail with a RepositoryError when the batch repository rejects the insert" in new TestContext {
        val organizationID                      = arbitrarySample[OrganizationID]
        val catalogueItemPostRequest1           = arbitrarySample[CatalogueItemPostRequest]
        val catalogueItemPostRequest2           = arbitrarySample[CatalogueItemPostRequest]
        val catalogueItemBatchPostRequestSmithy = smithy.CatalogueItemBatchPostRequest(
          List(
            catalogueItemPostRequest1.transformInto[smithy.CatalogueItemPostRequest],
            catalogueItemPostRequest2.transformInto[smithy.CatalogueItemPostRequest],
          )
        )

        catalogueRepositoryMock.insertCatalogueItems
          .expects(
            organizationID,
            NonEmptyChunk.fromIterable(
              toInsertCatalogueItemInput(catalogueItemPostRequest1),
              List(toInsertCatalogueItemInput(catalogueItemPostRequest2)),
            ),
          )
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemBatchPost(organizationID.value, catalogueItemBatchPostRequestSmithy)
          .zioError shouldBe repositoryError
      }

    }

    "catalogueItemPut" should {
      "update the catalogue item and preserve empty variant changes" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val catalogueItemID               = arbitrarySample[CatalogueItemID]
        val catalogueItemPutRequestSmithy = smithy.CatalogueItemPutRequest()

        catalogueRepositoryMock.updateCatalogueItem
          .expects(organizationID, catalogueItemID, None, None, None, Nil, Nil)
          .returningZIO(())
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPut(organizationID.value, catalogueItemID.value, catalogueItemPutRequestSmithy)
          .zioValue shouldBe ()
      }

      "map item and variant keep set clear fields and insert new variants as Active" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val catalogueItemID              = arbitrarySample[CatalogueItemID]
        val catalogueItemName            = arbitrarySample[CatalogueItemName]
        val catalogueItemDescription     = arbitrarySample[CatalogueItemDescription]
        val catalogueItemVariantIDUpdate = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantUpdate   = arbitrarySample[CatalogueItemVariantUpdate].copy(
          catalogueItemVariantID = catalogueItemVariantIDUpdate,
          sku = None,
          label = Some(Some(arbitrarySample[CatalogueItemVariantLabel])),
          description = Some(None),
          attributes = Some(Map.empty),
          price = Some(None),
          sellingUnit = Some(arbitrarySample[CatalogueItemVariantSaleUnit]),
          status = Some(CatalogueItemVariantStatus.Inactive),
        )
        val catalogueItemVariantInsert = arbitrarySample[CatalogueItemVariantInsert]
        val catalogueItemPutRequest    = CatalogueItemPutRequest(
          name = Some(catalogueItemName),
          description = Some(Some(catalogueItemDescription)),
          variantsUpdate = List(catalogueItemVariantUpdate),
          variantsInsert = List(catalogueItemVariantInsert),
        )
        val catalogueItemPutRequestSmithy = catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest]

        catalogueRepositoryMock.updateCatalogueItem
          .expects(
            organizationID,
            catalogueItemID,
            Some(catalogueItemName),
            Some(Some(catalogueItemDescription)),
            None,
            List(toUpdateCatalogueItemVariantInput(catalogueItemVariantUpdate)),
            List(toInsertCatalogueItemVariantInput(catalogueItemVariantInsert)),
          )
          .returningZIO(())
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPut(organizationID.value, catalogueItemID.value, catalogueItemPutRequestSmithy)
          .zioValue shouldBe ()
      }

      "clear the item description and nullable variant fields" in new TestContext {
        val organizationID               = arbitrarySample[OrganizationID]
        val catalogueItemID              = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantIDUpdate = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantUpdate   = arbitrarySample[CatalogueItemVariantUpdate].copy(
          catalogueItemVariantID = catalogueItemVariantIDUpdate,
          sku = Some(None),
          label = Some(None),
          description = Some(None),
          attributes = None,
          price = Some(None),
          sellingUnit = None,
          status = None,
        )
        val catalogueItemPutRequest = CatalogueItemPutRequest(
          name = None,
          description = Some(None),
          variantsUpdate = List(catalogueItemVariantUpdate),
          variantsInsert = Nil,
        )
        val catalogueItemPutRequestSmithy = catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest]

        catalogueRepositoryMock.updateCatalogueItem
          .expects(
            organizationID,
            catalogueItemID,
            None,
            Some(None),
            None,
            List(toUpdateCatalogueItemVariantInput(catalogueItemVariantUpdate)),
            Nil,
          )
          .returningZIO(())
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPut(organizationID.value, catalogueItemID.value, catalogueItemPutRequestSmithy)
          .zioValue shouldBe ()
      }

      "fail with a ValidationError before repository access when the update is invalid" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        val catalogueItemPutRequestSmithy = smithy.CatalogueItemPutRequest(name = Some(""))

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPut(organizationID.value, catalogueItemID.value, catalogueItemPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              ServiceError.BadRequestError.InvalidFieldError(
                "name",
                invalidTextMessage,
                List(""),
              )
            )
          )
      }

      "fail with a ValidationError before repository access when an update disables a variant" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val catalogueItemID                  = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantUpdateSmithy = arbitrarySample[smithy.CatalogueItemVariantUpdate].copy(
          status = Some(smithy.CatalogueItemVariantStatus.Archived)
        )
        val catalogueItemPutRequestSmithy = smithy.CatalogueItemPutRequest(
          variantsUpdate = List(catalogueItemVariantUpdateSmithy)
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPut(organizationID.value, catalogueItemID.value, catalogueItemPutRequestSmithy)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              nestedError(
                "variant",
                0,
                ServiceError.BadRequestError.InvalidFieldError(
                  "status",
                  "Disabled status is not allowed for catalogue item variant updates",
                  List("Archived"),
                ),
              )
            )
          )
      }

      "fail with a RepositoryError when the update repository rejects the change" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val catalogueItemID               = arbitrarySample[CatalogueItemID]
        val catalogueItemPutRequest       = arbitrarySample[CatalogueItemPutRequest]
        val catalogueItemPutRequestSmithy =
          catalogueItemPutRequest.transformInto[smithy.CatalogueItemPutRequest]

        catalogueRepositoryMock.updateCatalogueItem
          .expects(
            organizationID,
            catalogueItemID,
            catalogueItemPutRequest.name,
            catalogueItemPutRequest.description,
            None,
            catalogueItemPutRequest.variantsUpdate.map(toUpdateCatalogueItemVariantInput),
            catalogueItemPutRequest.variantsInsert.map(toInsertCatalogueItemVariantInput),
          )
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemPut(organizationID.value, catalogueItemID.value, catalogueItemPutRequestSmithy)
          .zioError shouldBe repositoryError
      }
    }

    "catalogueItemArchivePut" should {
      "archive the requested catalogue item" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.archiveCatalogueItem
          .expects(organizationID, catalogueItemID)
          .returningZIO(())
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemArchivePut(organizationID.value, catalogueItemID.value)
          .zioValue shouldBe ()
      }

      "fail with a RepositoryError when the archive repository rejects the change" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.archiveCatalogueItem
          .expects(organizationID, catalogueItemID)
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemArchivePut(organizationID.value, catalogueItemID.value)
          .zioError shouldBe repositoryError
      }
    }

    "catalogueItemVariantArchivePut" should {
      "archive the requested catalogue item variant" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]

        catalogueRepositoryMock.archiveCatalogueItemVariant
          .expects(organizationID, catalogueItemID, catalogueItemVariantID)
          .returningZIO(())
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemVariantArchivePut(
            organizationID.value,
            catalogueItemID.value,
            catalogueItemVariantID.value,
          )
          .zioValue shouldBe ()
      }

      "fail with an InvalidAction when the repository rejects the last enabled variant archive" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]

        catalogueRepositoryMock.archiveCatalogueItemVariant
          .expects(organizationID, catalogueItemID, catalogueItemVariantID)
          .returns(
            ZIO.fail(
              ServiceError.ForbiddenError.InvalidAction(
                "archiveCatalogueItemVariant",
                "Cannot archive the last enabled catalogue item variant",
              )
            )
          )
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemVariantArchivePut(
            organizationID.value,
            catalogueItemID.value,
            catalogueItemVariantID.value,
          )
          .zioError shouldBe
          ServiceError.ForbiddenError.InvalidAction(
            "archiveCatalogueItemVariant",
            "Cannot archive the last enabled catalogue item variant",
          )
      }

      "fail with a RepositoryError when the variant archive repository rejects the change" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]

        catalogueRepositoryMock.archiveCatalogueItemVariant
          .expects(organizationID, catalogueItemID, catalogueItemVariantID)
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemVariantArchivePut(
            organizationID.value,
            catalogueItemID.value,
            catalogueItemVariantID.value,
          )
          .zioError shouldBe repositoryError
      }
    }

    "catalogueItemGet" should {
      "return the complete catalogue item with its variants and image URL" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemID         = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1 = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2 = arbitrarySample[CatalogueItemVariantID]
        catalogueItemVariantID1 should not be catalogueItemVariantID2
        val catalogueItemVariantPrice = arbitrarySample[CatalogueItemVariantPrice]
        val catalogueItemRow          = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          catalogueItemVariantID = catalogueItemVariantID1,
          price = Some(catalogueItemVariantPrice),
          status = CatalogueItemVariantStatus.Active,
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          catalogueItemVariantID = catalogueItemVariantID2,
          price = None,
          status = CatalogueItemVariantStatus.OutOfStock,
        )
        val catalogueItemWithVariantsRow = CatalogueItemWithVariantsRow(
          catalogueItemRow,
          NonEmptyChunk.fromIterable(catalogueItemVariantRow1, List(catalogueItemVariantRow2)),
        )
        val imageNormalizedUrl = arbitrarySample[S3MediaUrl]

        inSequence(
          catalogueRepositoryMock.getCatalogueItemWithVariantsActive
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemWithVariantsRow))
            .once(),
          s3ClientOrganizationMediaMock.genMediaUrl
            .expects(
              catalogueItemRow.imageAsset.value.value.imageNormalizedS3BucketKey.to[S3BucketKey]
            )
            .returningZIO(imageNormalizedUrl)
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioValue shouldBe toCatalogueItemGetResponse(catalogueItemWithVariantsRow, Some(imageNormalizedUrl))
      }

      "return the complete catalogue item without an image URL when no image exists" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemID  = arbitrarySample[CatalogueItemID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )
        val catalogueItemWithVariantsRow = CatalogueItemWithVariantsRow(
          catalogueItemRow,
          NonEmptyChunk.single(catalogueItemVariantRow),
        )

        catalogueRepositoryMock.getCatalogueItemWithVariantsActive
          .expects(organizationID, catalogueItemID)
          .returningZIO(Some(catalogueItemWithVariantsRow))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioValue shouldBe
          toCatalogueItemGetResponse(catalogueItemWithVariantsRow, None)
      }

      "fail with an UnexpectedError when no catalogue item exists" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.getCatalogueItemWithVariantsActive
          .expects(organizationID, catalogueItemID)
          .returningZIO(None)
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioError shouldBe
          ServiceError.InternalServerError.UnexpectedError(
            s"Catalogue item not found for catalogueItemID: [${catalogueItemID.value}]"
          )
      }

      "fail with a RepositoryError when the detail repository rejects the read" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.getCatalogueItemWithVariantsActive
          .expects(organizationID, catalogueItemID)
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioError shouldBe repositoryError
      }

      "fail with a RepositoryError when detail image URL signing rejects the read" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemID        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemRow       = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          catalogueItemVariantID = catalogueItemVariantID,
          status = CatalogueItemVariantStatus.Active,
        )
        val catalogueItemWithVariantsRow = CatalogueItemWithVariantsRow(
          catalogueItemRow,
          NonEmptyChunk.single(catalogueItemVariantRow),
        )

        inSequence(
          catalogueRepositoryMock.getCatalogueItemWithVariantsActive
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemWithVariantsRow))
            .once(),
          s3ClientOrganizationMediaMock.genMediaUrl
            .expects(
              catalogueItemRow.imageAsset.value.value.imageNormalizedS3BucketKey.to[S3BucketKey]
            )
            .returns(ZIO.fail(repositoryError))
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioError shouldBe repositoryError
      }

    }

    "catalogueItemSummaryGet" should {
      "return all enabled catalogue item summaries when no IDs are supplied" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemID         = arbitrarySample[CatalogueItemID]
        val catalogueItemSummaryRow = arbitrarySample[CatalogueItemSummaryRow].copy(
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )

        catalogueRepositoryMock.getAllCatalogueItemSummariesEnabled
          .expects(organizationID)
          .returningZIO(List(catalogueItemSummaryRow))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemSummaryGet(organizationID.value)
          .zioValue shouldBe
          smithy.CatalogueItemSummaryGetResponse(
            List(toCatalogueItemSummaryResponse(catalogueItemSummaryRow, None))
          )
      }

      "return populated summaries in item and variant order and sign present images" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemID1 = arbitrarySample[CatalogueItemID]
        val catalogueItemID2 = arbitrarySample[CatalogueItemID]
        catalogueItemID1 should not be catalogueItemID2
        val catalogueItemVariantSummaryRow1 = arbitrarySample[CatalogueItemVariantSummaryRow]
        val catalogueItemVariantSummaryRow2 = arbitrarySample[CatalogueItemVariantSummaryRow]
        catalogueItemVariantSummaryRow1.catalogueItemVariantID should not be
          catalogueItemVariantSummaryRow2.catalogueItemVariantID
        val catalogueItemVariantPrice               = arbitrarySample[CatalogueItemVariantPrice]
        val catalogueItemVariantSummaryRowWithPrice = catalogueItemVariantSummaryRow1.copy(
          price = Some(catalogueItemVariantPrice)
        )
        val catalogueItemVariantSummaryRowWithoutPrice = catalogueItemVariantSummaryRow2.copy(price = None)
        val catalogueItemSummaryRow1                   = arbitrarySample[CatalogueItemSummaryRow].copy(
          catalogueItemID = catalogueItemID1,
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
          status = CatalogueItemStatus.Active,
          catalogueItemVariantSummaryRows = NonEmptyChunk.fromIterable(
            catalogueItemVariantSummaryRowWithPrice,
            List(catalogueItemVariantSummaryRowWithoutPrice),
          ),
        )
        val catalogueItemSummaryRow2 = arbitrarySample[CatalogueItemSummaryRow].copy(
          catalogueItemID = catalogueItemID2,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemIDsSelected = NonEmptyChunk.fromIterable(
          catalogueItemSummaryRow1.catalogueItemID,
          List(catalogueItemSummaryRow2.catalogueItemID),
        )
        val catalogueItemImageNormalizedUrl = arbitrarySample[S3MediaUrl]

        inSequence(
          catalogueRepositoryMock.getCatalogueItemSummariesEnabled
            .expects(organizationID, catalogueItemIDsSelected)
            .returningZIO(List(catalogueItemSummaryRow1, catalogueItemSummaryRow2))
            .once(),
          s3ClientOrganizationMediaMock.genMediaUrl
            .expects(
              catalogueItemSummaryRow1.imageAsset.value.value.imageNormalizedS3BucketKey.to[S3BucketKey]
            )
            .returningZIO(catalogueItemImageNormalizedUrl)
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemSummaryGet(
            organizationID.value,
            catalogueItemIDsSelected.toList.map(_.value),
          )
          .zioValue shouldBe
          smithy.CatalogueItemSummaryGetResponse(
            List(
              toCatalogueItemSummaryResponse(catalogueItemSummaryRow1, Some(catalogueItemImageNormalizedUrl)),
              toCatalogueItemSummaryResponse(catalogueItemSummaryRow2, None),
            )
          )
      }

      "return no summaries and make no image calls when the repository is empty" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        catalogueRepositoryMock.getAllCatalogueItemSummariesEnabled
          .expects(organizationID)
          .returningZIO(Nil)
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemSummaryGet(organizationID.value)
          .zioValue shouldBe
          smithy.CatalogueItemSummaryGetResponse(Nil)
      }

      "fail with a RepositoryError when the all-summaries repository rejects the read" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        catalogueRepositoryMock.getAllCatalogueItemSummariesEnabled
          .expects(organizationID)
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemSummaryGet(organizationID.value)
          .zioError shouldBe repositoryError
      }

      "fail with a RepositoryError when the selected-summaries repository rejects the read" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.getCatalogueItemSummariesEnabled
          .expects(organizationID, NonEmptyChunk.single(catalogueItemID))
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemSummaryGet(organizationID.value, List(catalogueItemID.value))
          .zioError shouldBe repositoryError
      }

      "fail with a RepositoryError when summary image URL signing rejects the read" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemSummaryRow = arbitrarySample[CatalogueItemSummaryRow].copy(
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
          status = CatalogueItemStatus.Active,
        )

        inSequence(
          catalogueRepositoryMock.getAllCatalogueItemSummariesEnabled
            .expects(organizationID)
            .returningZIO(List(catalogueItemSummaryRow))
            .once(),
          s3ClientOrganizationMediaMock.genMediaUrl
            .expects(
              catalogueItemSummaryRow.imageAsset.value.value.imageNormalizedS3BucketKey.to[S3BucketKey]
            )
            .returns(ZIO.fail(repositoryError))
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemSummaryGet(organizationID.value)
          .zioError shouldBe repositoryError
      }
    }

    "catalogueItemUniquenessGet" should {
      "return both supplied uniqueness results" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemName       = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        inSequence(
          catalogueRepositoryMock.isUniqueCatalogueItemName
            .expects(organizationID, catalogueItemName)
            .returningZIO(true)
            .once(),
          catalogueRepositoryMock.isUniqueCatalogueItemVariantSKU
            .expects(organizationID, catalogueItemVariantSku)
            .returningZIO(true)
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(
            organizationID.value,
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
          )
          .zioValue shouldBe smithy.CatalogueItemUniquenessGetResponse(Some(true), Some(true))
      }

      "check only the supplied name" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        catalogueRepositoryMock.isUniqueCatalogueItemName
          .expects(organizationID, catalogueItemName)
          .returningZIO(false)
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(organizationID.value, Some(catalogueItemName.value))
          .zioValue shouldBe smithy.CatalogueItemUniquenessGetResponse(Some(false), None)
      }

      "check only the supplied SKU" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        catalogueRepositoryMock.isUniqueCatalogueItemVariantSKU
          .expects(organizationID, catalogueItemVariantSku)
          .returningZIO(false)
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(organizationID.value, None, Some(catalogueItemVariantSku.value))
          .zioValue shouldBe smithy.CatalogueItemUniquenessGetResponse(None, Some(false))
      }

      "keep false results present when both supplied values are taken" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemName       = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        inSequence(
          catalogueRepositoryMock.isUniqueCatalogueItemName
            .expects(organizationID, catalogueItemName)
            .returningZIO(false)
            .once(),
          catalogueRepositoryMock.isUniqueCatalogueItemVariantSKU
            .expects(organizationID, catalogueItemVariantSku)
            .returningZIO(false)
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(
            organizationID.value,
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
          )
          .zioValue shouldBe smithy.CatalogueItemUniquenessGetResponse(Some(false), Some(false))
      }

      "fail with a ValidationError before repository access when supplied uniqueness fields are invalid" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(organizationID.value, Some(""), Some(""))
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              ServiceError.BadRequestError.InvalidFieldError("name", invalidTextMessage, List("")),
              ServiceError.BadRequestError.InvalidFieldError("sku", invalidTextMessage, List("")),
            )
          )
      }

      "fail with a RepositoryError before checking SKU when name uniqueness rejects the read" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemName       = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        catalogueRepositoryMock.isUniqueCatalogueItemName
          .expects(organizationID, catalogueItemName)
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(
            organizationID.value,
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
          )
          .zioError shouldBe repositoryError
      }

      "fail with a RepositoryError after a successful name check when SKU uniqueness rejects the read" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemName       = arbitrarySample[CatalogueItemName]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        inSequence(
          catalogueRepositoryMock.isUniqueCatalogueItemName
            .expects(organizationID, catalogueItemName)
            .returningZIO(true)
            .once(),
          catalogueRepositoryMock.isUniqueCatalogueItemVariantSKU
            .expects(organizationID, catalogueItemVariantSku)
            .returns(ZIO.fail(repositoryError))
            .once(),
        )

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(
            organizationID.value,
            Some(catalogueItemName.value),
            Some(catalogueItemVariantSku.value),
          )
          .zioError shouldBe repositoryError
      }

      "fail with a RepositoryError when name uniqueness rejects the read" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        catalogueRepositoryMock.isUniqueCatalogueItemName
          .expects(organizationID, catalogueItemName)
          .returns(ZIO.fail(repositoryError))
          .once()

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(organizationID.value, Some(catalogueItemName.value))
          .zioError shouldBe repositoryError
      }

      "fail with a ValidationError containing both missing uniqueness fields" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        val catalogueService = buildCatalogueService

        catalogueService
          .catalogueItemUniquenessGet(organizationID.value)
          .zioError shouldBe
          ServiceError.BadRequestError.ValidationError(
            List(
              ServiceError.BadRequestError.InvalidFieldError(
                "name",
                "At least one of name or sku must be provided",
                Seq.empty,
              ),
              ServiceError.BadRequestError.InvalidFieldError(
                "sku",
                "At least one of name or sku must be provided",
                Seq.empty,
              ),
            )
          )
      }
    }
  }

  trait TestContext {
    val catalogueRepositoryMock       = mock[CatalogueRepository]
    val s3ClientOrganizationMediaMock = mock[S3ClientOrganizationMedia]

    def buildCatalogueService: smithy.CatalogueService[ServiceTask] =
      ZIO
        .service[smithy.CatalogueService[ServiceTask]]
        .provide(
          CatalogueService.local,
          CatalogueRequestValidator.live,
          PriceValidator.live,
          ZLayer.succeed(catalogueRepositoryMock),
          ZLayer.succeed(s3ClientOrganizationMediaMock),
        )
        .zioValue
  }
}
