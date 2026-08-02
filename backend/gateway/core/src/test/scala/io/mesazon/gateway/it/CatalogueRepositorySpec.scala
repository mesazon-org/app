package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.CatalogueRepository.InsertCatalogueItemInput
import io.mesazon.gateway.repository.domain.{CatalogueItemRow, CatalogueItemSummaryRow}
import io.mesazon.gateway.repository.queries.CatalogueItemQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant

class CatalogueRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val testContext = new TestContext {}
    import testContext.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.catalogueItemTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val testContext = new TestContext {}
    import testContext.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.catalogueItemTable).zioValue
    }
  }

  "CatalogueRepository" when {
    "insertCatalogueItem" should {
      "insert an active catalogue item with an exact price and no image metadata" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemID          = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemInput = arbitrarySample[InsertCatalogueItemInput].copy(
          price = Some(arbitrarySample[CatalogueItemPrice])
        )
        val catalogueItemRowExpected = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          name = insertCatalogueItemInput.name,
          unit = insertCatalogueItemInput.unit,
          price = insertCatalogueItemInput.price,
          image = None,
          status = CatalogueItemStatus.Active,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID.value).once(),
        )

        catalogueRepository
          .insertCatalogueItem(organizationID, insertCatalogueItemInput)
          .zioValue shouldBe catalogueItemID

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
      }

      "fail with a UniqueConstraintViolation when an active name already exists in the organization" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemID1         = arbitrarySample[CatalogueItemID]
        val catalogueItemID2         = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemInput = arbitrarySample[InsertCatalogueItemInput]
        val catalogueItemRowExpected = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID1,
          name = insertCatalogueItemInput.name,
          unit = insertCatalogueItemInput.unit,
          price = insertCatalogueItemInput.price,
          image = None,
          status = CatalogueItemStatus.Active,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue

        val serviceError = catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
      }

      "allow the same active name in another organization" in new TestContext {
        val organizationID1           = arbitrarySample[OrganizationID]
        val organizationID2           = arbitrarySample[OrganizationID]
        val catalogueItemID1          = arbitrarySample[CatalogueItemID]
        val catalogueItemID2          = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemInput  = arbitrarySample[InsertCatalogueItemInput]
        val catalogueItemRow1Expected = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID1,
          catalogueItemID = catalogueItemID1,
          name = insertCatalogueItemInput.name,
          unit = insertCatalogueItemInput.unit,
          price = insertCatalogueItemInput.price,
          image = None,
          status = CatalogueItemStatus.Active,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemRow2Expected = catalogueItemRow1Expected.copy(
          organizationID = organizationID2,
          catalogueItemID = catalogueItemID2,
        )

        catalogueItemRow1Expected.organizationID shouldNot equal(catalogueItemRow2Expected.organizationID)
        catalogueItemRow1Expected.catalogueItemID shouldNot equal(catalogueItemRow2Expected.catalogueItemID)
        catalogueItemRow1Expected.name shouldBe catalogueItemRow2Expected.name

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID1, insertCatalogueItemInput).zioValue

        catalogueRepository.insertCatalogueItem(organizationID2, insertCatalogueItemInput).zioValue

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue should
          contain theSameElementsAs List(catalogueItemRow1Expected, catalogueItemRow2Expected)
      }
    }

    "insertCatalogueItems" should {
      "insert multiple catalogue items atomically and return generated IDs in input order" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemID1          = arbitrarySample[CatalogueItemID]
        val catalogueItemID2          = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemInput1 = arbitrarySample[InsertCatalogueItemInput].copy(price = None)
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput].copy(
          price = Some(arbitrarySample[CatalogueItemPrice])
        )
        val insertCatalogueItemInputs = List(insertCatalogueItemInput1, insertCatalogueItemInput2)
        val catalogueItemRow1Expected = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID1,
          name = insertCatalogueItemInput1.name,
          unit = insertCatalogueItemInput1.unit,
          price = insertCatalogueItemInput1.price,
          image = None,
          status = CatalogueItemStatus.Active,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemRow2Expected = catalogueItemRow1Expected.copy(
          catalogueItemID = catalogueItemID2,
          name = insertCatalogueItemInput2.name,
          unit = insertCatalogueItemInput2.unit,
          price = insertCatalogueItemInput2.price,
        )

        catalogueItemRow1Expected.catalogueItemID shouldNot equal(catalogueItemRow2Expected.catalogueItemID)
        catalogueItemRow1Expected.name shouldNot equal(catalogueItemRow2Expected.name)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
        )

        catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioValue shouldBe
          List(catalogueItemID1, catalogueItemID2)

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue should
          contain theSameElementsAs List(catalogueItemRow1Expected, catalogueItemRow2Expected)
      }

      "return an empty result without generating time or IDs for an empty batch" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val insertCatalogueItemInputs = List.empty[InsertCatalogueItemInput]

        catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioValue shouldBe Nil

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "fail with a UniqueConstraintViolation and roll back when the batch contains duplicate names" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemID1          = arbitrarySample[CatalogueItemID]
        val catalogueItemID2          = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemInput1 = arbitrarySample[InsertCatalogueItemInput]
        val insertCatalogueItemInput2 =
          arbitrarySample[InsertCatalogueItemInput].copy(name = insertCatalogueItemInput1.name)
        val insertCatalogueItemInputs = List(insertCatalogueItemInput1, insertCatalogueItemInput2)

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
        )

        val serviceError =
          catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }
    }

    "updateCatalogueItem" should {
      "update the supplied name, price, and image metadata while retaining the omitted unit" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )
        val catalogueItemNameUpdate  = arbitrarySample[CatalogueItemName]
        val catalogueItemPriceUpdate = arbitrarySample[CatalogueItemPrice]
        val catalogueItemImageUpdate = arbitrarySample[CatalogueItemImage]
        val catalogueItemRowExpected = catalogueItemRow.copy(
          name = catalogueItemNameUpdate,
          price = Some(catalogueItemPriceUpdate),
          image = Some(catalogueItemImageUpdate),
          updatedAt = UpdatedAt(instantNow),
        )

        catalogueItemNameUpdate shouldNot equal(catalogueItemRow.name)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        val catalogueItemRowUpdatedOpt = catalogueRepository
          .updateCatalogueItem(
            catalogueItemRow.organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(catalogueItemNameUpdate),
            unitOptUpdate = None,
            priceOptUpdate = Some(catalogueItemPriceUpdate),
            imageOptUpdate = Some(catalogueItemImageUpdate),
          )
          .zioValue

        catalogueItemRowUpdatedOpt shouldBe Some(catalogueItemRowExpected)

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
      }

      "update only updatedAt when no business fields are supplied" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )
        val catalogueItemRowExpected = catalogueItemRow.copy(
          updatedAt = UpdatedAt(instantNow)
        )

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .updateCatalogueItem(catalogueItemRow.organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe
          Some(catalogueItemRowExpected)

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
      }

      "return None and leave the row unchanged when no catalogue item matches the ID" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )
        val catalogueItemIDMissing  = arbitrarySample[CatalogueItemID]
        val catalogueItemNameUpdate = arbitrarySample[CatalogueItemName]

        catalogueItemIDMissing shouldNot equal(catalogueItemRow.catalogueItemID)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .updateCatalogueItem(
            catalogueItemRow.organizationID,
            catalogueItemIDMissing,
            nameOptUpdate = Some(catalogueItemNameUpdate),
          )
          .zioValue shouldBe None

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "return None and leave the row unchanged for another organization" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )
        val organizationIDForeign   = arbitrarySample[OrganizationID]
        val catalogueItemNameUpdate = arbitrarySample[CatalogueItemName]

        organizationIDForeign shouldNot equal(catalogueItemRow.organizationID)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .updateCatalogueItem(
            organizationIDForeign,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(catalogueItemNameUpdate),
          )
          .zioValue shouldBe None

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "return None and leave an archived catalogue item unchanged" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Archived
        )
        val catalogueItemNameUpdate = arbitrarySample[CatalogueItemName]

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .updateCatalogueItem(
            catalogueItemRow.organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(catalogueItemNameUpdate),
          )
          .zioValue shouldBe None

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "fail with a UniqueConstraintViolation when renaming to an active same-organization name" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemRow1 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          status = CatalogueItemStatus.Active,
        )

        catalogueItemRow1.catalogueItemID shouldNot equal(catalogueItemRow2.catalogueItemID)
        catalogueItemRow1.name shouldNot equal(catalogueItemRow2.name)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRows(List(catalogueItemRow1, catalogueItemRow2)))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow2.catalogueItemID,
            nameOptUpdate = Some(catalogueItemRow1.name),
          )
          .zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue should
          contain theSameElementsAs List(catalogueItemRow1, catalogueItemRow2)
      }
    }

    "archiveCatalogueItem" should {
      "archive an active catalogue item, retain it, and free its active name" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          price = Some(arbitrarySample[CatalogueItemPrice]),
          image = Some(arbitrarySample[CatalogueItemImage]),
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemRowArchivedExpected = catalogueItemRow.copy(
          status = CatalogueItemStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemIDNew       = arbitrarySample[CatalogueItemID]
        val insertCatalogueItemInput = arbitrarySample[InsertCatalogueItemInput].copy(
          name = catalogueItemRow.name
        )
        val catalogueItemRowNewExpected = arbitrarySample[CatalogueItemRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemIDNew,
          name = insertCatalogueItemInput.name,
          unit = insertCatalogueItemInput.unit,
          price = insertCatalogueItemInput.price,
          image = None,
          status = CatalogueItemStatus.Active,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        catalogueItemIDNew shouldNot equal(catalogueItemRow.catalogueItemID)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .archiveCatalogueItem(catalogueItemRow.organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe
          Some(catalogueItemRow.catalogueItemID)

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchivedExpected)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemIDNew.value).once(),
        )

        catalogueRepository
          .insertCatalogueItem(catalogueItemRow.organizationID, insertCatalogueItemInput)
          .zioValue shouldBe catalogueItemIDNew

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue should
          contain theSameElementsAs
          List(catalogueItemRowArchivedExpected, catalogueItemRowNewExpected)
      }

      "return None and leave the row unchanged when the catalogue item is already archived" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Archived
        )

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .archiveCatalogueItem(catalogueItemRow.organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe None

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }

      "return None when no catalogue item matches the ID" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemID)
          .zioValue shouldBe None

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
      }

      "return None and leave the row unchanged for another organization" in new TestContext {
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationIDForeign shouldNot equal(catalogueItemRow.organizationID)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        (() => timeProviderMock.instantNow)
          .expects()
          .returningZIO(instantNow)
          .once()

        catalogueRepository
          .archiveCatalogueItem(
            organizationIDForeign,
            catalogueItemRow.catalogueItemID,
          )
          .zioValue shouldBe None

        postgresClient.executeQuery(catalogueItemQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
      }
    }

    "getCatalogueItem" should {
      "get active and archived catalogue items for the supplied organization" in new TestContext {
        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = catalogueItemRowActive.organizationID,
          status = CatalogueItemStatus.Archived,
        )

        catalogueItemRowActive.catalogueItemID shouldNot equal(catalogueItemRowArchived.catalogueItemID)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowActive))
          .zioValue
        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          .zioValue

        catalogueRepository
          .getCatalogueItem(
            catalogueItemRowActive.organizationID,
            catalogueItemRowActive.catalogueItemID,
          )
          .zioValue shouldBe Some(catalogueItemRowActive)

        catalogueRepository
          .getCatalogueItem(
            catalogueItemRowArchived.organizationID,
            catalogueItemRowArchived.catalogueItemID,
          )
          .zioValue shouldBe Some(catalogueItemRowArchived)
      }

      "return None when no catalogue item matches the ID" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepository
          .getCatalogueItem(organizationID, catalogueItemID)
          .zioValue shouldBe None
      }

      "return None for another organization" in new TestContext {
        val catalogueItemRow      = arbitrarySample[CatalogueItemRow]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationIDForeign shouldNot equal(catalogueItemRow.organizationID)

        postgresClient
          .executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRow))
          .zioValue

        catalogueRepository
          .getCatalogueItem(organizationIDForeign, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe None
      }
    }

    "getCatalogueItemsActive" should {
      "return an empty list when the organization has no active catalogue items" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        catalogueRepository.getCatalogueItemsActive(organizationID).zioValue shouldBe Nil
      }

      "return only active catalogue items for the supplied organization" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemRowActive1 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemRowActive2 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          status = CatalogueItemStatus.Archived,
        )
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          status = CatalogueItemStatus.Active
        )

        catalogueItemRowActive1.catalogueItemID shouldNot equal(catalogueItemRowActive2.catalogueItemID)
        catalogueItemRowActive1.name shouldNot equal(catalogueItemRowActive2.name)
        catalogueItemRowArchived.catalogueItemID shouldNot equal(catalogueItemRowActive1.catalogueItemID)
        catalogueItemRowArchived.catalogueItemID shouldNot equal(catalogueItemRowActive2.catalogueItemID)
        catalogueItemRowForeign.organizationID shouldNot equal(organizationID)

        postgresClient
          .executeQuery(
            catalogueItemQueries.insertCatalogueItemRows(
              List(
                catalogueItemRowActive1,
                catalogueItemRowActive2,
                catalogueItemRowArchived,
                catalogueItemRowForeign,
              )
            )
          )
          .zioValue

        catalogueRepository.getCatalogueItemsActive(organizationID).zioValue should contain theSameElementsAs
          List(catalogueItemRowActive1, catalogueItemRowActive2).map(catalogueItemRow =>
            CatalogueItemSummaryRow(
              catalogueItemID = catalogueItemRow.catalogueItemID,
              name = catalogueItemRow.name,
              image = catalogueItemRow.image,
              status = catalogueItemRow.status,
            )
          )
      }

      "map an unexpected database failure to RepositoryError with its underlying exception" in new TestContext {
        val catalogueItemQueriesInvalid = ZIO
          .service[CatalogueItemQueries]
          .provide(
            CatalogueItemQueries.live,
            ZLayer.succeed(repositoryConfig.copy(catalogueItemTable = "not_a_catalogue_table")),
          )
          .zioValue
        val catalogueRepositoryInvalid = ZIO
          .service[CatalogueRepository]
          .provide(
            CatalogueRepository.live,
            postgresClient.databaseLive,
            ZLayer.succeed(catalogueItemQueriesInvalid),
            ZLayer.succeed(timeProviderMock),
            ZLayer.succeed(idGeneratorMock),
          )
          .zioValue
        val organizationID = arbitrarySample[OrganizationID]

        val serviceError = catalogueRepositoryInvalid.getCatalogueItemsActive(organizationID).zioError

        serviceError shouldBe a[ServiceError.InternalServerError.RepositoryError]
        serviceError.message shouldBe s"Failed to get catalogue items for organization ID: [$organizationID]"
        serviceError.underlying.value shouldBe a[DbException]
      }
    }
  }

  trait TestContext {
    val instantNow: Instant = arbitrarySample[CreatedAt].value

    val repositoryConfig: RepositoryConfig = RepositoryConfig(
      schema = "local_schema",
      catalogueItemTable = "catalogue_item",
    )

    val postgresClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgresClientConfig))
      .zioValue

    val catalogueItemQueries = ZIO
      .service[CatalogueItemQueries]
      .provide(CatalogueItemQueries.live, ZLayer.succeed(repositoryConfig))
      .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val catalogueRepository = ZIO
      .service[CatalogueRepository]
      .provide(
        CatalogueRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(catalogueItemQueries),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
      )
      .zioValue
  }
}
