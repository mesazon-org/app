package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.to
import io.mesazon.gateway.clients.S3ClientOrganizationMedia
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.CatalogueRepository.InsertCatalogueItemInput
import io.mesazon.gateway.repository.domain.{CatalogueItemRow, CatalogueItemSummaryRow}
import io.mesazon.gateway.service.{CatalogueService, ServiceTask}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.validation.domain.PriceDomainValidator
import io.mesazon.gateway.validation.service.CatalogueRequestValidator
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import zio.*

class CatalogueServiceSpec extends ZWordSpecBase, CatalogueSmithyArbitraries, RepositoryArbitraries {

  private val nonEmptyTrimmedError =
    "Should not have leading or trailing whitespaces & Should have a minimum length of 1 & Should have a maximum length of 255"

  private val repositoryError =
    ServiceError.InternalServerError.RepositoryError("repository failure", new RuntimeException("boom"))

  private def toInsertCatalogueItemInput(
      insertCatalogueItemPostRequest: InsertCatalogueItemPostRequest
  ): InsertCatalogueItemInput =
    InsertCatalogueItemInput(
      name = insertCatalogueItemPostRequest.name,
      unit = insertCatalogueItemPostRequest.unit,
      price = insertCatalogueItemPostRequest.price,
    )

  private def toCatalogueItemPriceRequest(
      catalogueItemPrice: CatalogueItemPrice
  ): smithy.CatalogueItemPriceRequest =
    smithy.CatalogueItemPriceRequest(
      amount = catalogueItemPrice.value.amount.value,
      currency = catalogueItemPrice.value.currency.value,
    )

  "CatalogueService" when {
    "insertCatalogueItemPost" should {
      "successfully insert a catalogue item" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemID                = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemPostRequest = arbitrarySample[InsertCatalogueItemPostRequest]
        val insertCatalogueItemInput       = toInsertCatalogueItemInput(insertCatalogueItemPostRequest)

        catalogueRepositoryMock.insertCatalogueItem
          .expects(organizationID, insertCatalogueItemInput)
          .returningZIO(catalogueItemID)
          .once()

        buildCatalogueService
          .insertCatalogueItemPost(
            organizationID.value,
            insertCatalogueItemPostRequest.transformInto[smithy.InsertCatalogueItemPostRequest],
          )
          .zioValue
          .shouldBe(())
      }

      "fail with a ValidationError and never reach the repository when the name is invalid" in new TestContext {
        val organizationID                       = arbitrarySample[OrganizationID]
        val insertCatalogueItemPostRequestSmithy =
          arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(name = "")

        buildCatalogueService
          .insertCatalogueItemPost(organizationID.value, insertCatalogueItemPostRequestSmithy)
          .zioError
          .shouldBe(
            ServiceError.BadRequestError.ValidationError(
              List(InvalidFieldError("name", nonEmptyTrimmedError, List("")))
            )
          )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val insertCatalogueItemPostRequest = arbitrarySample[InsertCatalogueItemPostRequest]

        catalogueRepositoryMock.insertCatalogueItem
          .expects(organizationID, toInsertCatalogueItemInput(insertCatalogueItemPostRequest))
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCatalogueService
          .insertCatalogueItemPost(
            organizationID.value,
            insertCatalogueItemPostRequest.transformInto[smithy.InsertCatalogueItemPostRequest],
          )
          .zioError
          .shouldBe(repositoryError)
      }
    }

    "insertCatalogueItemsPost" should {
      "successfully insert an atomic batch of catalogue items" in new TestContext {
        val organizationID                  = arbitrarySample[OrganizationID]
        val insertCatalogueItemsPostRequest = arbitrarySample[InsertCatalogueItemsPostRequest]
        val insertCatalogueItemInputs       =
          insertCatalogueItemsPostRequest.catalogueItems.map(toInsertCatalogueItemInput)
        val catalogueItemIDs = insertCatalogueItemInputs.map(_ => arbitrarySample[CatalogueItemID])

        catalogueRepositoryMock.insertCatalogueItems
          .expects(organizationID, insertCatalogueItemInputs)
          .returningZIO(catalogueItemIDs)
          .once()

        buildCatalogueService
          .insertCatalogueItemsPost(
            organizationID.value,
            insertCatalogueItemsPostRequest.transformInto[smithy.InsertCatalogueItemsPostRequest],
          )
          .zioValue
          .shouldBe(())
      }

      "fail with a ValidationError and never reach the repository when a batch item is invalid" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val insertCatalogueItemsPostRequestSmithy = smithy.InsertCatalogueItemsPostRequest(
          List(arbitrarySample[smithy.InsertCatalogueItemPostRequest].copy(unit = ""))
        )

        buildCatalogueService
          .insertCatalogueItemsPost(organizationID.value, insertCatalogueItemsPostRequestSmithy)
          .zioError
          .shouldBe(
            ServiceError.BadRequestError.ValidationError(
              List(
                InvalidFieldError(
                  "catalogueItem",
                  s"Failed with invalid fields [${InvalidFieldError("unit", nonEmptyTrimmedError, List(""))}]",
                  List(),
                  index = 0,
                )
              )
            )
          )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID                  = arbitrarySample[OrganizationID]
        val insertCatalogueItemsPostRequest = arbitrarySample[InsertCatalogueItemsPostRequest]
        val insertCatalogueItemInputs       =
          insertCatalogueItemsPostRequest.catalogueItems.map(toInsertCatalogueItemInput)

        catalogueRepositoryMock.insertCatalogueItems
          .expects(organizationID, insertCatalogueItemInputs)
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCatalogueService
          .insertCatalogueItemsPost(
            organizationID.value,
            insertCatalogueItemsPostRequest.transformInto[smithy.InsertCatalogueItemsPostRequest],
          )
          .zioError
          .shouldBe(repositoryError)
      }
    }

    "updateCatalogueItemPut" should {
      "successfully update a catalogue item" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val updateCatalogueItemPutRequest = arbitrarySample[UpdateCatalogueItemPutRequest]
        val catalogueItemRowUpdated       = arbitrarySample[CatalogueItemRow]

        catalogueRepositoryMock.updateCatalogueItem
          .expects(
            organizationID,
            updateCatalogueItemPutRequest.catalogueItemID,
            updateCatalogueItemPutRequest.name,
            updateCatalogueItemPutRequest.unit,
            updateCatalogueItemPutRequest.price,
            None,
          )
          .returningZIO(Some(catalogueItemRowUpdated))
          .once()

        buildCatalogueService
          .updateCatalogueItemPut(
            organizationID.value,
            updateCatalogueItemPutRequest.transformInto[smithy.UpdateCatalogueItemPutRequest],
          )
          .zioValue
          .shouldBe(())
      }

      "silently succeed when no active catalogue item matches the id" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val updateCatalogueItemPutRequest = arbitrarySample[UpdateCatalogueItemPutRequest]

        catalogueRepositoryMock.updateCatalogueItem
          .expects(
            organizationID,
            updateCatalogueItemPutRequest.catalogueItemID,
            updateCatalogueItemPutRequest.name,
            updateCatalogueItemPutRequest.unit,
            updateCatalogueItemPutRequest.price,
            None,
          )
          .returningZIO(None)
          .once()

        buildCatalogueService
          .updateCatalogueItemPut(
            organizationID.value,
            updateCatalogueItemPutRequest.transformInto[smithy.UpdateCatalogueItemPutRequest],
          )
          .zioValue
          .shouldBe(())
      }

      "fail with a ValidationError and never reach the repository when the unit is invalid" in new TestContext {
        val organizationID                      = arbitrarySample[OrganizationID]
        val updateCatalogueItemPutRequestSmithy =
          arbitrarySample[smithy.UpdateCatalogueItemPutRequest].copy(unit = Some(""))

        buildCatalogueService
          .updateCatalogueItemPut(organizationID.value, updateCatalogueItemPutRequestSmithy)
          .zioError
          .shouldBe(
            ServiceError.BadRequestError.ValidationError(
              List(InvalidFieldError("unit", nonEmptyTrimmedError, List("")))
            )
          )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID                = arbitrarySample[OrganizationID]
        val updateCatalogueItemPutRequest = arbitrarySample[UpdateCatalogueItemPutRequest]

        catalogueRepositoryMock.updateCatalogueItem
          .expects(
            organizationID,
            updateCatalogueItemPutRequest.catalogueItemID,
            updateCatalogueItemPutRequest.name,
            updateCatalogueItemPutRequest.unit,
            updateCatalogueItemPutRequest.price,
            None,
          )
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCatalogueService
          .updateCatalogueItemPut(
            organizationID.value,
            updateCatalogueItemPutRequest.transformInto[smithy.UpdateCatalogueItemPutRequest],
          )
          .zioError
          .shouldBe(repositoryError)
      }
    }

    "archiveCatalogueItemPut" should {
      "successfully archive a catalogue item" in new TestContext {
        val organizationID                       = arbitrarySample[OrganizationID]
        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]
        val catalogueItemID                      = CatalogueItemID(archiveCatalogueItemPutRequestSmithy.catalogueItemID)

        catalogueRepositoryMock.archiveCatalogueItem
          .expects(organizationID, catalogueItemID)
          .returningZIO(Some(catalogueItemID))
          .once()

        buildCatalogueService
          .archiveCatalogueItemPut(organizationID.value, archiveCatalogueItemPutRequestSmithy)
          .zioValue
          .shouldBe(())
      }

      "silently succeed when no active catalogue item matches the id" in new TestContext {
        val organizationID                       = arbitrarySample[OrganizationID]
        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]

        catalogueRepositoryMock.archiveCatalogueItem
          .expects(organizationID, CatalogueItemID(archiveCatalogueItemPutRequestSmithy.catalogueItemID))
          .returningZIO(None)
          .once()

        buildCatalogueService
          .archiveCatalogueItemPut(organizationID.value, archiveCatalogueItemPutRequestSmithy)
          .zioValue
          .shouldBe(())
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID                       = arbitrarySample[OrganizationID]
        val archiveCatalogueItemPutRequestSmithy = arbitrarySample[smithy.ArchiveCatalogueItemPutRequest]

        catalogueRepositoryMock.archiveCatalogueItem
          .expects(organizationID, CatalogueItemID(archiveCatalogueItemPutRequestSmithy.catalogueItemID))
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCatalogueService
          .archiveCatalogueItemPut(organizationID.value, archiveCatalogueItemPutRequestSmithy)
          .zioError
          .shouldBe(repositoryError)
      }
    }

    "getCatalogueItemGet" should {
      "successfully return a catalogue item's full details" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset])
        )
        val catalogueItemImageOriginalUrl   = arbitrarySample[S3MediaUrl]
        val catalogueItemImageNormalizedUrl = arbitrarySample[S3MediaUrl]

        catalogueRepositoryMock.getCatalogueItem
          .expects(organizationID, catalogueItemRow.catalogueItemID)
          .returningZIO(Some(catalogueItemRow))
          .once()

        s3ClientOrganizationMediaMock.genMediaUrl
          .expects(catalogueItemRow.imageAsset.value.value.imageOriginalS3BucketKey.to[S3BucketKey])
          .returningZIO(catalogueItemImageOriginalUrl)
          .once()

        s3ClientOrganizationMediaMock.genMediaUrl
          .expects(catalogueItemRow.imageAsset.value.value.imageNormalizedS3BucketKey.to[S3BucketKey])
          .returningZIO(catalogueItemImageNormalizedUrl)
          .once()

        buildCatalogueService
          .getCatalogueItemGet(organizationID.value, catalogueItemRow.catalogueItemID.value)
          .zioValue
          .shouldBe(
            smithy.GetCatalogueItemGetResponse(
              catalogueItemID = catalogueItemRow.catalogueItemID.value,
              name = catalogueItemRow.name.value,
              unit = catalogueItemRow.unit.value,
              price = catalogueItemRow.price.map(toCatalogueItemPriceRequest),
              imageOriginalUrl = Some(catalogueItemImageOriginalUrl.value),
              imageNormalizedUrl = Some(catalogueItemImageNormalizedUrl.value),
            )
          )
      }

      "successfully return a catalogue item without image URLs when it has no image" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(imageAsset = None)

        catalogueRepositoryMock.getCatalogueItem
          .expects(organizationID, catalogueItemRow.catalogueItemID)
          .returningZIO(Some(catalogueItemRow))
          .once()

        buildCatalogueService
          .getCatalogueItemGet(organizationID.value, catalogueItemRow.catalogueItemID.value)
          .zioValue
          .shouldBe(
            smithy.GetCatalogueItemGetResponse(
              catalogueItemID = catalogueItemRow.catalogueItemID.value,
              name = catalogueItemRow.name.value,
              unit = catalogueItemRow.unit.value,
              price = catalogueItemRow.price.map(toCatalogueItemPriceRequest),
              imageOriginalUrl = None,
              imageNormalizedUrl = None,
            )
          )
      }

      "fail with an UnexpectedError when the catalogue item does not exist" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.getCatalogueItem
          .expects(organizationID, catalogueItemID)
          .returningZIO(None)
          .once()

        buildCatalogueService
          .getCatalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioError
          .shouldBe(
            ServiceError.InternalServerError.UnexpectedError(
              s"Catalogue item not found for catalogueItemID: [${catalogueItemID.value}]"
            )
          )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepositoryMock.getCatalogueItem
          .expects(organizationID, catalogueItemID)
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCatalogueService
          .getCatalogueItemGet(organizationID.value, catalogueItemID.value)
          .zioError
          .shouldBe(repositoryError)
      }
    }

    "getCatalogueItemsGet" should {
      "successfully return every active catalogue item in the repository's order" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemSummaryRow1 = arbitrarySample[CatalogueItemSummaryRow].copy(
          imageAsset = Some(arbitrarySample[CatalogueItemImageAsset]),
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemSummaryRow2 = arbitrarySample[CatalogueItemSummaryRow].copy(
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemImageNormalizedUrl = arbitrarySample[S3MediaUrl]

        catalogueItemSummaryRow1 should not be catalogueItemSummaryRow2

        catalogueRepositoryMock.getCatalogueItemsActive
          .expects(organizationID)
          .returningZIO(List(catalogueItemSummaryRow1, catalogueItemSummaryRow2))
          .once()

        s3ClientOrganizationMediaMock.genMediaUrl
          .expects(catalogueItemSummaryRow1.imageAsset.value.value.imageNormalizedS3BucketKey.to[S3BucketKey])
          .returningZIO(catalogueItemImageNormalizedUrl)
          .once()

        buildCatalogueService
          .getCatalogueItemsGet(organizationID.value)
          .zioValue
          .shouldBe(
            smithy.GetCatalogueItemsGetResponse(
              List(
                smithy.GetCatalogueItem(
                  catalogueItemID = catalogueItemSummaryRow1.catalogueItemID.value,
                  name = catalogueItemSummaryRow1.name.value,
                  status = smithy.CatalogueItemStatus.ACTIVE,
                  imageNormalizedUrl = Some(catalogueItemImageNormalizedUrl.value),
                ),
                smithy.GetCatalogueItem(
                  catalogueItemID = catalogueItemSummaryRow2.catalogueItemID.value,
                  name = catalogueItemSummaryRow2.name.value,
                  status = smithy.CatalogueItemStatus.ACTIVE,
                  imageNormalizedUrl = None,
                ),
              )
            )
          )
      }

      "fail with a RepositoryError and surface it unchanged" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        catalogueRepositoryMock.getCatalogueItemsActive
          .expects(organizationID)
          .returns(ZIO.fail(repositoryError))
          .once()

        buildCatalogueService.getCatalogueItemsGet(organizationID.value).zioError.shouldBe(repositoryError)
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
          PriceDomainValidator.live,
          ZLayer.succeed(catalogueRepositoryMock),
          ZLayer.succeed(s3ClientOrganizationMediaMock),
        )
        .zioValue
  }
}
