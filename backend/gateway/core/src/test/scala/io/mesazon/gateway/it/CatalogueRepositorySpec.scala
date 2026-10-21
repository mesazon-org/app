package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.CatalogueRepository
import io.mesazon.gateway.repository.CatalogueRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CatalogueQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class CatalogueRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    repositoryConfig.allTableNames.foreach { tableName =>
      eventually {
        postgresClient.checkIfTableExists(repositoryConfig.schema, tableName).zioValue shouldBe true
      }
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    repositoryConfig.allTableNames.foreach { tableName =>
      eventually {
        postgresClient.truncateTable(repositoryConfig.schema, tableName).zioValue
      }
    }
  }

  "CatalogueRepository" when {
    "insertCatalogueItem" should {
      "insert a catalogue item and its variant rows in one transaction" in new TestContext {
        val organizationID                  = arbitrarySample[OrganizationID]
        val catalogueItemID                 = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID          = arbitrarySample[CatalogueItemVariantID]
        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput        = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID.value).once(),
        )

        val catalogueItemRowExpected = CatalogueItemRow(
          organizationID,
          catalogueItemID,
          insertCatalogueItemInput.name,
          insertCatalogueItemInput.description,
          None,
          CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected = CatalogueItemVariantRow(
          organizationID,
          catalogueItemVariantID,
          catalogueItemID,
          insertCatalogueItemVariantInput.sku,
          insertCatalogueItemVariantInput.label,
          insertCatalogueItemVariantInput.description,
          insertCatalogueItemVariantInput.attributes,
          insertCatalogueItemVariantInput.price,
          insertCatalogueItemVariantInput.sellingUnit,
          insertCatalogueItemVariantInput.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemID

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowExpected)
      }

      "insert a catalogue item and its three variant rows in one transaction" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val catalogueItemID                  = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID3          = arbitrarySample[CatalogueItemVariantID]
        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput3 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput         = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants =
            NonEmptyChunk(
              insertCatalogueItemVariantInput1,
              insertCatalogueItemVariantInput2,
              insertCatalogueItemVariantInput3,
            )
          )

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID3.value).once(),
        )

        val catalogueItemVariantRowExpected1 = CatalogueItemVariantRow(
          organizationID,
          catalogueItemVariantID1,
          catalogueItemID,
          insertCatalogueItemVariantInput1.sku,
          insertCatalogueItemVariantInput1.label,
          insertCatalogueItemVariantInput1.description,
          insertCatalogueItemVariantInput1.attributes,
          insertCatalogueItemVariantInput1.price,
          insertCatalogueItemVariantInput1.sellingUnit,
          insertCatalogueItemVariantInput1.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected2 = catalogueItemVariantRowExpected1.copy(
          catalogueItemVariantID = catalogueItemVariantID2,
          sku = insertCatalogueItemVariantInput2.sku,
          label = insertCatalogueItemVariantInput2.label,
          description = insertCatalogueItemVariantInput2.description,
          attributes = insertCatalogueItemVariantInput2.attributes,
          price = insertCatalogueItemVariantInput2.price,
          sellingUnit = insertCatalogueItemVariantInput2.sellingUnit,
          status = insertCatalogueItemVariantInput2.status,
        )
        val catalogueItemVariantRowExpected3 = catalogueItemVariantRowExpected1.copy(
          catalogueItemVariantID = catalogueItemVariantID3,
          sku = insertCatalogueItemVariantInput3.sku,
          label = insertCatalogueItemVariantInput3.label,
          description = insertCatalogueItemVariantInput3.description,
          attributes = insertCatalogueItemVariantInput3.attributes,
          price = insertCatalogueItemVariantInput3.price,
          sellingUnit = insertCatalogueItemVariantInput3.sellingUnit,
          status = insertCatalogueItemVariantInput3.status,
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemID

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue
          .toSet shouldBe
          Set(catalogueItemVariantRowExpected1, catalogueItemVariantRowExpected2, catalogueItemVariantRowExpected3)
      }

      "insert a catalogue item whose name matches an archived catalogue item's name, because the partial unique index excludes archived rows" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val catalogueItemIDNonArchived = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID     = arbitrarySample[CatalogueItemVariantID]
        val sharedName                 = arbitrarySample[CatalogueItemName]

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          name = sharedName,
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue

        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput        = arbitrarySample[InsertCatalogueItemInput]
          .copy(name = sharedName, variants = NonEmptyChunk.single(insertCatalogueItemVariantInput))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemIDNonArchived.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemIDNonArchived

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue
          .map(_.catalogueItemID)
          .toSet shouldBe Set(catalogueItemRowArchived.catalogueItemID, catalogueItemIDNonArchived)
      }

      "insert a catalogue item variant when an archived variant has the same case-insensitive SKU" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val catalogueItemIDArchived           = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantIDArchived    = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemIDNonArchived        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantIDNonArchived = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantSkuArchived   = CatalogueItemVariantSku("archived-sku")
        val catalogueItemVariantSkuInsert     = CatalogueItemVariantSku("ARCHIVED-SKU")

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemIDArchived,
          isArchived = true,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemIDArchived,
          catalogueItemVariantID = catalogueItemVariantIDArchived,
          sku = Some(catalogueItemVariantSkuArchived),
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived)))
          .zioValue

        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(catalogueItemVariantSkuInsert))
        val insertCatalogueItemInput = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemIDNonArchived.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantIDNonArchived.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemIDNonArchived

        val catalogueItemRowExpected = CatalogueItemRow(
          organizationID,
          catalogueItemIDNonArchived,
          insertCatalogueItemInput.name,
          insertCatalogueItemInput.description,
          None,
          CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected = CatalogueItemVariantRow(
          organizationID,
          catalogueItemVariantIDNonArchived,
          catalogueItemIDNonArchived,
          insertCatalogueItemVariantInput.sku,
          insertCatalogueItemVariantInput.label,
          insertCatalogueItemVariantInput.description,
          insertCatalogueItemVariantInput.attributes,
          insertCatalogueItemVariantInput.price,
          insertCatalogueItemVariantInput.sellingUnit,
          insertCatalogueItemVariantInput.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRowArchived, catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowArchived, catalogueItemVariantRowExpected)
      }

      "fail with a UniqueConstraintViolation and roll back the item insert when a case-insensitive SKU is already in use" in new TestContext {
        val organizationID                  = arbitrarySample[OrganizationID]
        val catalogueItemID1                = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1         = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2         = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantSkuExisting = CatalogueItemVariantSku("single-sku")
        val catalogueItemVariantSkuInsert   = CatalogueItemVariantSku("SINGLE-SKU")

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(catalogueItemVariantSkuExisting))
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(catalogueItemVariantSkuInsert))
        val insertCatalogueItemInput1 = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput1))
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput2))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput1).zioValue

        val serviceError =
          catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput2).zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item variant with the given SKU already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        val catalogueItemRowExpected = CatalogueItemRow(
          organizationID,
          catalogueItemID1,
          insertCatalogueItemInput1.name,
          insertCatalogueItemInput1.description,
          None,
          CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected = CatalogueItemVariantRow(
          organizationID,
          catalogueItemVariantID1,
          catalogueItemID1,
          insertCatalogueItemVariantInput1.sku,
          insertCatalogueItemVariantInput1.label,
          insertCatalogueItemVariantInput1.description,
          insertCatalogueItemVariantInput1.attributes,
          insertCatalogueItemVariantInput1.price,
          insertCatalogueItemVariantInput1.sellingUnit,
          insertCatalogueItemVariantInput1.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowExpected)
      }

      "fail with a UniqueConstraintViolation when a catalogue item name already exists in the organization, rolling back the second item" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val catalogueItemID1                 = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                 = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2          = arbitrarySample[CatalogueItemVariantID]
        val sharedName                       = arbitrarySample[CatalogueItemName]
        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        val insertCatalogueItemInput1 = arbitrarySample[InsertCatalogueItemInput]
          .copy(name = sharedName, variants = NonEmptyChunk.single(insertCatalogueItemVariantInput1))
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput]
          .copy(name = sharedName, variants = NonEmptyChunk.single(insertCatalogueItemVariantInput2))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput1).zioValue

        val serviceError =
          catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput2).zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue
          .map(_.catalogueItemID) shouldBe
          List(catalogueItemID1)
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue
          .map(_.catalogueItemVariantID) shouldBe List(catalogueItemVariantID1)
      }
    }

    "insertCatalogueItems" should {
      "insert multiple catalogue items with varying variant counts atomically in one transaction" in new TestContext {
        val organizationID                    = arbitrarySample[OrganizationID]
        val catalogueItemID1                  = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                  = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1a          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID1b          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2           = arbitrarySample[CatalogueItemVariantID]
        val insertCatalogueItemVariantInput1a = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput1b = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput2  = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput1         = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk(insertCatalogueItemVariantInput1a, insertCatalogueItemVariantInput1b))
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput2))
        val insertCatalogueItemInputs =
          NonEmptyChunk(insertCatalogueItemInput1, insertCatalogueItemInput2)

        catalogueItemID1 shouldNot equal(catalogueItemID2)
        insertCatalogueItemInput1.name shouldNot equal(insertCatalogueItemInput2.name)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1a.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1b.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
        )

        val catalogueItemRowExpected1 = CatalogueItemRow(
          organizationID,
          catalogueItemID1,
          insertCatalogueItemInput1.name,
          insertCatalogueItemInput1.description,
          None,
          CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemRowExpected2 = catalogueItemRowExpected1.copy(
          catalogueItemID = catalogueItemID2,
          name = insertCatalogueItemInput2.name,
          description = insertCatalogueItemInput2.description,
        )
        val catalogueItemVariantRowExpected1a = CatalogueItemVariantRow(
          organizationID,
          catalogueItemVariantID1a,
          catalogueItemID1,
          insertCatalogueItemVariantInput1a.sku,
          insertCatalogueItemVariantInput1a.label,
          insertCatalogueItemVariantInput1a.description,
          insertCatalogueItemVariantInput1a.attributes,
          insertCatalogueItemVariantInput1a.price,
          insertCatalogueItemVariantInput1a.sellingUnit,
          insertCatalogueItemVariantInput1a.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected1b = catalogueItemVariantRowExpected1a.copy(
          catalogueItemVariantID = catalogueItemVariantID1b,
          sku = insertCatalogueItemVariantInput1b.sku,
          label = insertCatalogueItemVariantInput1b.label,
          description = insertCatalogueItemVariantInput1b.description,
          attributes = insertCatalogueItemVariantInput1b.attributes,
          price = insertCatalogueItemVariantInput1b.price,
          sellingUnit = insertCatalogueItemVariantInput1b.sellingUnit,
          status = insertCatalogueItemVariantInput1b.status,
        )
        val catalogueItemVariantRowExpected2 = catalogueItemVariantRowExpected1a.copy(
          catalogueItemVariantID = catalogueItemVariantID2,
          catalogueItemID = catalogueItemID2,
          sku = insertCatalogueItemVariantInput2.sku,
          label = insertCatalogueItemVariantInput2.label,
          description = insertCatalogueItemVariantInput2.description,
          attributes = insertCatalogueItemVariantInput2.attributes,
          price = insertCatalogueItemVariantInput2.price,
          sellingUnit = insertCatalogueItemVariantInput2.sellingUnit,
          status = insertCatalogueItemVariantInput2.status,
        )

        catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioValue shouldBe
          NonEmptyChunk(catalogueItemID1, catalogueItemID2)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected1, catalogueItemRowExpected2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue
          .toSet shouldBe
          Set(catalogueItemVariantRowExpected1a, catalogueItemVariantRowExpected1b, catalogueItemVariantRowExpected2)
      }

      "fail with a UniqueConstraintViolation and roll back the whole batch when two items in the batch share a name" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val catalogueItemID1                 = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                 = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2          = arbitrarySample[CatalogueItemVariantID]
        val sharedName                       = arbitrarySample[CatalogueItemName]
        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput1        = arbitrarySample[InsertCatalogueItemInput]
          .copy(name = sharedName, variants = NonEmptyChunk.single(insertCatalogueItemVariantInput1))
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput]
          .copy(name = sharedName, variants = NonEmptyChunk.single(insertCatalogueItemVariantInput2))
        val insertCatalogueItemInputs = NonEmptyChunk(insertCatalogueItemInput1, insertCatalogueItemInput2)

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
        )

        val serviceError =
          catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a UniqueConstraintViolation and roll back the batch when variants have the same case-insensitive SKU" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val catalogueItemID1                 = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                 = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantSku1         = CatalogueItemVariantSku("batch-sku")
        val catalogueItemVariantSku2         = CatalogueItemVariantSku("BATCH-SKU")
        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(catalogueItemVariantSku1))
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(catalogueItemVariantSku2))
        val insertCatalogueItemInput1 = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput1))
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput2))
        val insertCatalogueItemInputs = NonEmptyChunk(insertCatalogueItemInput1, insertCatalogueItemInput2)

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
        )

        val serviceError =
          catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item variant with the given SKU already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a UniqueConstraintViolation and roll back the whole batch when one item's name matches a catalogue item committed in a previous transaction" in new TestContext {
        val organizationID                          = arbitrarySample[OrganizationID]
        val catalogueItemIDExisting                 = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantIDExisting          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemID1                        = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1                 = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2                 = arbitrarySample[CatalogueItemVariantID]
        val insertCatalogueItemVariantInputExisting = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInputExisting        = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInputExisting))
        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput1        = arbitrarySample[InsertCatalogueItemInput]
          .copy(
            name = insertCatalogueItemInputExisting.name,
            variants = NonEmptyChunk.single(insertCatalogueItemVariantInput1),
          )
        val insertCatalogueItemInput2 = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput2))
        val insertCatalogueItemInputs = NonEmptyChunk(insertCatalogueItemInput1, insertCatalogueItemInput2)

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemIDExisting.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantIDExisting.value).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemID2.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID1.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID2.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInputExisting).zioValue shouldBe
          catalogueItemIDExisting

        val serviceError =
          catalogueRepository.insertCatalogueItems(organizationID, insertCatalogueItemInputs).zioError
        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        val catalogueItemRowExistingExpected = CatalogueItemRow(
          organizationID,
          catalogueItemIDExisting,
          insertCatalogueItemInputExisting.name,
          insertCatalogueItemInputExisting.description,
          None,
          CatalogueItemStatus.Active,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExistingExpected = CatalogueItemVariantRow(
          organizationID,
          catalogueItemVariantIDExisting,
          catalogueItemIDExisting,
          insertCatalogueItemVariantInputExisting.sku,
          insertCatalogueItemVariantInputExisting.label,
          insertCatalogueItemVariantInputExisting.description,
          insertCatalogueItemVariantInputExisting.attributes,
          insertCatalogueItemVariantInputExisting.price,
          insertCatalogueItemVariantInputExisting.sellingUnit,
          insertCatalogueItemVariantInputExisting.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExistingExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowExistingExpected)
      }
    }

    "updateCatalogueItem" should {
      "update only the catalogue item's own fields and preserve its status when status is omitted" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val nameUpdate        = arbitrarySample[CatalogueItemName]
        val descriptionUpdate = arbitrarySample[CatalogueItemDescription]
        val imageAssetUpdate  = arbitrarySample[CatalogueItemImageAsset]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(nameUpdate),
            descriptionOptUpdate = Some(Some(descriptionUpdate)),
            imageAssetOptUpdate = Some(imageAssetUpdate),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        val catalogueItemRowExpected = catalogueItemRow.copy(
          name = nameUpdate,
          description = Some(descriptionUpdate),
          imageAsset = Some(imageAssetUpdate),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "update an item's status in both directions without archiving it or changing its variants" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          status = CatalogueItemStatus.Active,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
        )

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            statusOptUpdate = Some(CatalogueItemStatus.Inactive),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        val catalogueItemRowInactiveExpected = catalogueItemRow.copy(
          status = CatalogueItemStatus.Inactive,
          isArchived = false,
          updatedAt = UpdatedAt(instantNow),
        )

        val catalogueItemRowsInactive =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue

        catalogueItemRowsInactive.head.status shouldNot equal(catalogueItemRow.status)
        catalogueItemRowsInactive shouldBe List(catalogueItemRowInactiveExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            statusOptUpdate = Some(CatalogueItemStatus.Active),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        val catalogueItemRowActiveExpected = catalogueItemRowInactiveExpected.copy(
          status = CatalogueItemStatus.Active
        )

        val catalogueItemRowsActive =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue

        catalogueItemRowsActive.head.status shouldNot equal(catalogueItemRowInactiveExpected.status)
        catalogueItemRowsActive shouldBe List(catalogueItemRowActiveExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "update only the specified fields of a catalogue item variant, leaving the rest unchanged" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val skuUpdate                       = arbitrarySample[CatalogueItemVariantSku]
        val updateCatalogueItemVariantInput = UpdateCatalogueItemVariantInput(
          catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
          sku = Some(Some(skuUpdate)),
          label = None,
          description = None,
          attributes = None,
          price = None,
          sellingUnit = None,
          status = None,
        )

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        val catalogueItemVariantRowExpected = catalogueItemVariantRow.copy(
          sku = Some(skuUpdate),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowExpected)
      }

      "update a variant's attributes, selling unit, and status while preserving its other fields and sibling variant" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantAttributeNameUpdate  = arbitrarySample[CatalogueItemVariantAttributeName]
        val catalogueItemVariantAttributeValueUpdate = arbitrarySample[CatalogueItemVariantAttributeValue]
        val catalogueItemVariantAttributesUpdate     =
          Map(catalogueItemVariantAttributeNameUpdate -> catalogueItemVariantAttributeValueUpdate)
        val catalogueItemVariantSaleUnitUpdate = arbitrarySample[CatalogueItemVariantSaleUnit]
        val catalogueItemVariantUnitExisting   = CatalogueItemVariantUnit.values
          .find(_ != catalogueItemVariantSaleUnitUpdate.quantity.unit)
          .value
        val catalogueItemVariantSaleUnitExisting = catalogueItemVariantSaleUnitUpdate.copy(
          quantity = catalogueItemVariantSaleUnitUpdate.quantity.copy(unit = catalogueItemVariantUnitExisting)
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = None,
          attributes = Map.empty,
          sellingUnit = catalogueItemVariantSaleUnitExisting,
          isArchived = false,
        )
        val catalogueItemVariantStatusUpdate = CatalogueItemVariantStatus.values
          .find(_ != catalogueItemVariantRow.status)
          .value
        val catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = None,
          isArchived = false,
        )

        catalogueItemVariantAttributesUpdate shouldNot equal(catalogueItemVariantRow.attributes)
        catalogueItemVariantSaleUnitUpdate shouldNot equal(catalogueItemVariantRow.sellingUnit)
        catalogueItemVariantStatusUpdate shouldNot equal(catalogueItemVariantRow.status)
        catalogueItemVariantRow.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowSibling.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )
          .zioValue

        val updateCatalogueItemVariantInput = UpdateCatalogueItemVariantInput(
          catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
          sku = None,
          label = None,
          description = None,
          attributes = Some(catalogueItemVariantAttributesUpdate),
          price = None,
          sellingUnit = Some(catalogueItemVariantSaleUnitUpdate),
          status = Some(catalogueItemVariantStatusUpdate),
        )

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        val catalogueItemRowExpected        = catalogueItemRow.copy(updatedAt = UpdatedAt(instantNow))
        val catalogueItemVariantRowExpected = catalogueItemVariantRow.copy(
          attributes = catalogueItemVariantAttributesUpdate,
          sellingUnit = catalogueItemVariantSaleUnitUpdate,
          status = catalogueItemVariantStatusUpdate,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowExpected, catalogueItemVariantRowSibling)
      }

      "update multiple variants in one batch, each keeping unspecified fields" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow3 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        Set(
          catalogueItemVariantRow1.catalogueItemVariantID,
          catalogueItemVariantRow2.catalogueItemVariantID,
          catalogueItemVariantRow3.catalogueItemVariantID,
        ) should have size 3

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow1, catalogueItemVariantRow2, catalogueItemVariantRow3)
            )
          )
          .zioValue

        val skuUpdate         = arbitrarySample[CatalogueItemVariantSku]
        val labelUpdate       = arbitrarySample[CatalogueItemVariantLabel]
        val descriptionUpdate = arbitrarySample[CatalogueItemVariantDescription]

        val updateCatalogueItemVariantInput1 = UpdateCatalogueItemVariantInput(
          catalogueItemVariantID = catalogueItemVariantRow1.catalogueItemVariantID,
          sku = Some(Some(skuUpdate)),
          label = None,
          description = None,
          attributes = None,
          price = None,
          sellingUnit = None,
          status = None,
        )
        val updateCatalogueItemVariantInput2 = UpdateCatalogueItemVariantInput(
          catalogueItemVariantID = catalogueItemVariantRow2.catalogueItemVariantID,
          sku = None,
          label = Some(Some(labelUpdate)),
          description = None,
          attributes = None,
          price = None,
          sellingUnit = None,
          status = None,
        )
        val updateCatalogueItemVariantInput3 = UpdateCatalogueItemVariantInput(
          catalogueItemVariantID = catalogueItemVariantRow3.catalogueItemVariantID,
          sku = None,
          label = None,
          description = Some(Some(descriptionUpdate)),
          attributes = None,
          price = None,
          sellingUnit = None,
          status = None,
        )

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            updateCatalogueItemVariantInputs = List(
              updateCatalogueItemVariantInput1,
              updateCatalogueItemVariantInput2,
              updateCatalogueItemVariantInput3,
            ),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        val catalogueItemVariantRowExpected1 = catalogueItemVariantRow1.copy(
          sku = Some(skuUpdate),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected2 = catalogueItemVariantRow2.copy(
          label = Some(labelUpdate),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected3 = catalogueItemVariantRow3.copy(
          description = Some(descriptionUpdate),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowExpected1, catalogueItemVariantRowExpected2, catalogueItemVariantRowExpected3)
      }

      "insert new variants while also updating an existing variant, within the same transaction" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val labelUpdate                     = arbitrarySample[CatalogueItemVariantLabel]
        val updateCatalogueItemVariantInput = UpdateCatalogueItemVariantInput(
          catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
          sku = None,
          label = Some(Some(labelUpdate)),
          description = None,
          attributes = None,
          price = None,
          sellingUnit = None,
          status = None,
        )
        val newCatalogueItemVariantID       = arbitrarySample[CatalogueItemVariantID]
        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(newCatalogueItemVariantID.value).once(),
        )

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = List(insertCatalogueItemVariantInput),
          )
          .zioValue shouldBe (())

        val catalogueItemVariantRowUpdatedExpected = catalogueItemVariantRow.copy(
          label = Some(labelUpdate),
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowInsertedExpected = CatalogueItemVariantRow(
          organizationID,
          newCatalogueItemVariantID,
          catalogueItemRow.catalogueItemID,
          insertCatalogueItemVariantInput.sku,
          insertCatalogueItemVariantInput.label,
          insertCatalogueItemVariantInput.description,
          insertCatalogueItemVariantInput.attributes,
          insertCatalogueItemVariantInput.price,
          insertCatalogueItemVariantInput.sellingUnit,
          insertCatalogueItemVariantInput.status,
          isArchived = false,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowUpdatedExpected, catalogueItemVariantRowInsertedExpected)
      }

      "serialize two concurrent updates of different fields of the same variant through the item row lock, so neither change is lost" in new TestContext {
        // Each update reads the variant, merges its own field, and writes the whole row back. Without the item row
        // lock serializing the two transactions, the second writer overwrites the first one's field with its stale read.
        val iterations = 50

        val catalogueItemRowsWithVariantRowsAndUpdates = List.fill(iterations) {
          val organizationID   = arbitrarySample[OrganizationID]
          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            label = None,
            price = None,
            isArchived = false,
          )
          (
            catalogueItemRow = catalogueItemRow,
            catalogueItemVariantRow = catalogueItemVariantRow,
            nameUpdate = arbitrarySample[CatalogueItemName],
            labelUpdate = arbitrarySample[CatalogueItemVariantLabel],
            priceUpdate = arbitrarySample[CatalogueItemVariantPrice],
          )
        }

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(
              catalogueItemRowsWithVariantRowsAndUpdates.map(_.catalogueItemRow)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              catalogueItemRowsWithVariantRowsAndUpdates.map(_.catalogueItemVariantRow)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).repeat(iterations * 2)

        ZIO
          .foreachDiscard(catalogueItemRowsWithVariantRowsAndUpdates) { catalogueItemRowWithVariantRowAndUpdates =>
            val catalogueItemRow                     = catalogueItemRowWithVariantRowAndUpdates.catalogueItemRow
            val catalogueItemVariantRow              = catalogueItemRowWithVariantRowAndUpdates.catalogueItemVariantRow
            val updateCatalogueItemVariantInputLabel = UpdateCatalogueItemVariantInput(
              catalogueItemVariantID = catalogueItemVariantRow.catalogueItemVariantID,
              sku = None,
              label = Some(Some(catalogueItemRowWithVariantRowAndUpdates.labelUpdate)),
              description = None,
              attributes = None,
              price = None,
              sellingUnit = None,
              status = None,
            )
            val updateCatalogueItemVariantInputPrice = updateCatalogueItemVariantInputLabel.copy(
              label = None,
              price = Some(Some(catalogueItemRowWithVariantRowAndUpdates.priceUpdate)),
            )

            catalogueRepository
              .updateCatalogueItem(
                catalogueItemRow.organizationID,
                catalogueItemRow.catalogueItemID,
                nameOptUpdate = Some(catalogueItemRowWithVariantRowAndUpdates.nameUpdate),
                updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInputLabel),
                insertCatalogueItemVariantInputs = Nil,
              )
              .zipPar(
                catalogueRepository.updateCatalogueItem(
                  catalogueItemRow.organizationID,
                  catalogueItemRow.catalogueItemID,
                  updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInputPrice),
                  insertCatalogueItemVariantInputs = Nil,
                )
              )
          }
          .zioValue

        val catalogueItemRowsExpected =
          catalogueItemRowsWithVariantRowsAndUpdates.map(catalogueItemRowWithVariantRowAndUpdates =>
            catalogueItemRowWithVariantRowAndUpdates.catalogueItemRow.copy(
              name = catalogueItemRowWithVariantRowAndUpdates.nameUpdate,
              updatedAt = UpdatedAt(instantNow),
            )
          )
        val catalogueItemVariantRowsExpected =
          catalogueItemRowsWithVariantRowsAndUpdates.map(catalogueItemRowWithVariantRowAndUpdates =>
            catalogueItemRowWithVariantRowAndUpdates.catalogueItemVariantRow.copy(
              label = Some(catalogueItemRowWithVariantRowAndUpdates.labelUpdate),
              price = Some(catalogueItemRowWithVariantRowAndUpdates.priceUpdate),
              updatedAt = UpdatedAt(instantNow),
            )
          )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          catalogueItemRowsExpected.toSet
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          catalogueItemVariantRowsExpected.toSet
      }

      "silently do nothing when the catalogue item does not exist" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemID,
            nameOptUpdate = Some(arbitrarySample[CatalogueItemName]),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "silently do nothing when the catalogue item is archived" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRowArchived.catalogueItemID,
            nameOptUpdate = Some(arbitrarySample[CatalogueItemName]),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
      }

      "silently do nothing when the catalogue item belongs to another organization" in new TestContext {
        val organizationID      = arbitrarySample[OrganizationID]
        val otherOrganizationID = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(otherOrganizationID)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = otherOrganizationID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign)).zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRowForeign.catalogueItemID,
            nameOptUpdate = Some(arbitrarySample[CatalogueItemName]),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowForeign)
      }

      "fail with an UnexpectedError and roll back the whole transaction when a variant ID does not exist" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        val missingCatalogueItemVariantID   = arbitrarySample[CatalogueItemVariantID]
        val updateCatalogueItemVariantInput = arbitrarySample[UpdateCatalogueItemVariantInput]
          .copy(catalogueItemVariantID = missingCatalogueItemVariantID)
        val nameUpdate = arbitrarySample[CatalogueItemName]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(nameUpdate),
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update catalogue item with ID: [${catalogueItemRow.catalogueItemID}]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are archived. Requested: [${List(missingCatalogueItemVariantID)}], Found: [List()]"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an UnexpectedError and roll back the whole transaction when a variant belongs to another catalogue item" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemRowA = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemRowB = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )

        catalogueItemRowA.catalogueItemID shouldNot equal(catalogueItemRowB.catalogueItemID)

        val catalogueItemVariantRowB = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowB.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowA)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowB)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowB)))
          .zioValue

        val updateCatalogueItemVariantInput = arbitrarySample[UpdateCatalogueItemVariantInput]
          .copy(catalogueItemVariantID = catalogueItemVariantRowB.catalogueItemVariantID)
        val nameUpdate = arbitrarySample[CatalogueItemName]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRowA.catalogueItemID,
            nameOptUpdate = Some(nameUpdate),
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update catalogue item with ID: [${catalogueItemRowA.catalogueItemID}]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are archived. Requested: [${List(catalogueItemVariantRowB.catalogueItemVariantID)}], Found: [List()]"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRowA, catalogueItemRowB)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowB)
      }

      "fail with an UnexpectedError and roll back the whole transaction when a variant is archived" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived)))
          .zioValue

        val updateCatalogueItemVariantInput = arbitrarySample[UpdateCatalogueItemVariantInput]
          .copy(catalogueItemVariantID = catalogueItemVariantRowArchived.catalogueItemVariantID)
        val nameUpdate = arbitrarySample[CatalogueItemName]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(nameUpdate),
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Failed to update catalogue item with ID: [${catalogueItemRow.catalogueItemID}]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are archived. Requested: [${List(catalogueItemVariantRowArchived.catalogueItemVariantID)}], Found: [List()]"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowArchived)
      }

      "fail with a UniqueConstraintViolation and roll back the item update when a case-insensitive SKU is already in use" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantSkuOriginal    = CatalogueItemVariantSku("update-original-sku")
        val catalogueItemVariantSkuReserved    = CatalogueItemVariantSku("update-reserved-sku")
        val catalogueItemVariantSkuCaseVariant = CatalogueItemVariantSku("UPDATE-RESERVED-SKU")
        val catalogueItemVariantRowUpdate      = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSkuOriginal),
          isArchived = false,
        )
        val catalogueItemVariantRowReserved = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSkuReserved),
          isArchived = false,
        )

        catalogueItemVariantRowUpdate.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowReserved.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowUpdate, catalogueItemVariantRowReserved)
            )
          )
          .zioValue

        val updateCatalogueItemVariantInput = arbitrarySample[UpdateCatalogueItemVariantInput].copy(
          catalogueItemVariantID = catalogueItemVariantRowUpdate.catalogueItemVariantID,
          sku = Some(Some(catalogueItemVariantSkuCaseVariant)),
          label = None,
          description = None,
          attributes = None,
          price = None,
          sellingUnit = None,
          status = None,
        )
        val catalogueItemNameUpdate = arbitrarySample[CatalogueItemName]

        catalogueItemNameUpdate shouldNot equal(catalogueItemRow.name)

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(catalogueItemNameUpdate),
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput),
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item variant with the given SKU already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowUpdate, catalogueItemVariantRowReserved)
      }

      "fail with a UniqueConstraintViolation and roll back the whole transaction when a new variant's SKU already exists" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val sharedSku               = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(sharedSku),
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val newCatalogueItemVariantID       = arbitrarySample[CatalogueItemVariantID]
        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(sharedSku))
        val nameUpdate = arbitrarySample[CatalogueItemName]

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(newCatalogueItemVariantID.value).once(),
        )

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            nameOptUpdate = Some(nameUpdate),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = List(insertCatalogueItemVariantInput),
          )
          .zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe
          "A catalogue item variant with the given SKU already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "fail with a UniqueConstraintViolation and roll back the whole transaction when the new name matches another non-archived catalogue item's name" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemRowA = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemRowB = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )

        catalogueItemRowA.catalogueItemID shouldNot equal(catalogueItemRowB.catalogueItemID)
        catalogueItemRowA.name shouldNot equal(catalogueItemRowB.name)

        val catalogueItemVariantRowA = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowA.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowB = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowB.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowA)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowB)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowA, catalogueItemVariantRowB))
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        val serviceError = catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRowA.catalogueItemID,
            nameOptUpdate = Some(catalogueItemRowB.name),
            updateCatalogueItemVariantInputs = Nil,
            insertCatalogueItemVariantInputs = Nil,
          )
          .zioError

        serviceError shouldBe a[ServiceError.ConflictError.UniqueConstraintViolation]
        serviceError.message shouldBe "A catalogue item with the given name already exists in this organization"
        serviceError.underlying.value shouldBe a[DbException]

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRowA, catalogueItemRowB)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowA, catalogueItemVariantRowB)
      }
    }

    "archiveCatalogueItem" should {
      "archive the catalogue item and every non-archived variant of it, preserving business statuses and leaving other catalogue items untouched" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          isArchived = false,
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        catalogueItemVariantRow1.catalogueItemVariantID shouldNot equal(catalogueItemVariantRow2.catalogueItemVariantID)
        catalogueItemRow.updatedAt shouldNot equal(UpdatedAt(instantNow))
        catalogueItemVariantRow1.updatedAt shouldNot equal(UpdatedAt(instantNow))
        catalogueItemVariantRow2.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowOther)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow1, catalogueItemVariantRow2, catalogueItemVariantRowOther)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe (())

        val catalogueItemRowExpected = catalogueItemRow.copy(
          isArchived = true,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected1 = catalogueItemVariantRow1.copy(
          isArchived = true,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected2 = catalogueItemVariantRow2.copy(
          isArchived = true,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRowExpected, catalogueItemRowOther)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowExpected1, catalogueItemVariantRowExpected2, catalogueItemVariantRowOther)
      }

      "leave an already-archived variant of the catalogue item untouched, including its updatedAt" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )

        catalogueItemVariantRowNonArchived.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowArchived.catalogueItemVariantID
        )
        catalogueItemVariantRowArchived.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived, catalogueItemVariantRowArchived)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe (())

        val catalogueItemRowExpected = catalogueItemRow.copy(
          isArchived = true,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowNonArchivedExpected = catalogueItemVariantRowNonArchived.copy(
          isArchived = true,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowNonArchivedExpected, catalogueItemVariantRowArchived)
      }

      "silently do nothing when the catalogue item is already archived, leaving its variants untouched" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowNonArchived)))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRowArchived.catalogueItemID)
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowNonArchived)
      }

      "silently do nothing when the catalogue item does not exist" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository.archiveCatalogueItem(organizationID, catalogueItemID).zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "silently do nothing when the catalogue item belongs to another organization, leaving its variants untouched" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(organizationIDForeign)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign)))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRowForeign.catalogueItemID)
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowForeign)
      }
    }

    "archiveCatalogueItem and updateCatalogueItem" should {
      "never leave a non-archived variant on an archived catalogue item when an archive and a variant-adding update run concurrently" in new TestContext {
        val iterations = 50

        val catalogueItemRowsWithVariantRowAndInsertInput = List.fill(iterations) {
          val organizationID   = arbitrarySample[OrganizationID]
          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            isArchived = false,
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          (
            catalogueItemRow = catalogueItemRow,
            catalogueItemVariantRow = catalogueItemVariantRow,
            catalogueItemVariantIDNew = arbitrarySample[CatalogueItemVariantID],
            insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput],
          )
        }

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(
              catalogueItemRowsWithVariantRowAndInsertInput.map(_.catalogueItemRow)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              catalogueItemRowsWithVariantRowAndInsertInput.map(_.catalogueItemVariantRow)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).repeat(iterations * 2)
        inSequence {
          catalogueItemRowsWithVariantRowAndInsertInput.foreach(catalogueItemRowWithVariantRowAndInsertInput =>
            (() => idGeneratorMock.generateID)
              .expects()
              .returningZIO(catalogueItemRowWithVariantRowAndInsertInput.catalogueItemVariantIDNew.value)
              .once()
          )
        }

        ZIO
          .foreachDiscard(catalogueItemRowsWithVariantRowAndInsertInput) {
            catalogueItemRowWithVariantRowAndInsertInput =>
              val catalogueItemRow = catalogueItemRowWithVariantRowAndInsertInput.catalogueItemRow

              catalogueRepository
                .archiveCatalogueItem(catalogueItemRow.organizationID, catalogueItemRow.catalogueItemID)
                .zipPar(
                  catalogueRepository.updateCatalogueItem(
                    catalogueItemRow.organizationID,
                    catalogueItemRow.catalogueItemID,
                    updateCatalogueItemVariantInputs = Nil,
                    insertCatalogueItemVariantInputs =
                      List(catalogueItemRowWithVariantRowAndInsertInput.insertCatalogueItemVariantInput),
                  )
                )
          }
          .zioValue

        val catalogueItemRowsExpected =
          catalogueItemRowsWithVariantRowAndInsertInput.map(catalogueItemRowWithVariantRowAndInsertInput =>
            catalogueItemRowWithVariantRowAndInsertInput.catalogueItemRow.copy(
              isArchived = true,
              updatedAt = UpdatedAt(instantNow),
            )
          )
        val catalogueItemVariantRowsExistingExpected =
          catalogueItemRowsWithVariantRowAndInsertInput.map(catalogueItemRowWithVariantRowAndInsertInput =>
            catalogueItemRowWithVariantRowAndInsertInput.catalogueItemVariantRow.copy(
              isArchived = true,
              updatedAt = UpdatedAt(instantNow),
            )
          )
        val catalogueItemVariantIDsExisting =
          catalogueItemVariantRowsExistingExpected.map(_.catalogueItemVariantID).toSet

        val catalogueItemVariantRows =
          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue
        val catalogueItemVariantRowsAdded = catalogueItemVariantRows.filterNot(catalogueItemVariantRow =>
          catalogueItemVariantIDsExisting.contains(catalogueItemVariantRow.catalogueItemVariantID)
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          catalogueItemRowsExpected.toSet
        catalogueItemVariantRows
          .filter(catalogueItemVariantRow =>
            catalogueItemVariantIDsExisting.contains(catalogueItemVariantRow.catalogueItemVariantID)
          )
          .toSet shouldBe catalogueItemVariantRowsExistingExpected.toSet
        every(catalogueItemVariantRowsAdded.map(_.isArchived)) shouldBe true
        catalogueItemVariantRowsAdded.foreach { catalogueItemVariantRowAdded =>
          val input = catalogueItemRowsWithVariantRowAndInsertInput
            .find(_.catalogueItemRow.catalogueItemID == catalogueItemVariantRowAdded.catalogueItemID)
            .value
            .insertCatalogueItemVariantInput
          catalogueItemVariantRowAdded.status shouldBe input.status
        }
      }
    }

    "archiveCatalogueItemVariant" should {
      "archive only the given non-archived variant, preserving business statuses and leaving its catalogue item, its other variants, and other catalogue items untouched" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow3 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          isArchived = false,
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        Set(
          catalogueItemVariantRow1.catalogueItemVariantID,
          catalogueItemVariantRow2.catalogueItemVariantID,
          catalogueItemVariantRow3.catalogueItemVariantID,
        ).size shouldBe 3
        catalogueItemVariantRow1.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowOther)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRow1,
                catalogueItemVariantRow2,
                catalogueItemVariantRow3,
                catalogueItemVariantRowOther,
              )
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRow1.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        val catalogueItemVariantRowExpected1 = catalogueItemVariantRow1.copy(
          isArchived = true,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRow, catalogueItemRowOther)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(
            catalogueItemVariantRowExpected1,
            catalogueItemVariantRow2,
            catalogueItemVariantRow3,
            catalogueItemVariantRowOther,
          )
      }

      "fail with InvalidAction and change nothing when the variant is the last non-archived variant of its catalogue item" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )

        catalogueItemVariantRowNonArchived.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowArchived.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived, catalogueItemVariantRowArchived)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRowNonArchived.catalogueItemVariantID,
          )
          .zioError shouldBe ServiceError.ForbiddenError.InvalidAction(
          "archiveCatalogueItemVariant",
          s"Catalogue item variant [${catalogueItemVariantRowNonArchived.catalogueItemVariantID}] is the last non-archived variant of catalogue item [${catalogueItemRow.catalogueItemID}]; archive the catalogue item instead",
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowNonArchived, catalogueItemVariantRowArchived)
      }

      "allow exactly one of two concurrent archives of a catalogue item's last two non-archived variants, rejecting the other with InvalidAction" in new TestContext {
        // Each archive counts the item's non-archived variants, then archives a different row. Without the item row lock
        // serializing the two transactions, both see two non-archived variants and both succeed, leaving none non-archived.
        val iterations = 50

        val catalogueItemRowsWithVariantRows = List.fill(iterations) {
          val organizationID   = arbitrarySample[OrganizationID]
          val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            isArchived = false,
          )
          (
            catalogueItemRow = catalogueItemRow,
            catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            ),
            catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            ),
          )
        }

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(catalogueItemRowsWithVariantRows.map(_.catalogueItemRow))
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              catalogueItemRowsWithVariantRows.flatMap(catalogueItemRowWithVariantRows =>
                List(
                  catalogueItemRowWithVariantRows.catalogueItemVariantRow1,
                  catalogueItemRowWithVariantRows.catalogueItemVariantRow2,
                )
              )
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).repeat(iterations * 2)

        val catalogueItemRowsWithVariantRowsAndArchiveResults = ZIO
          .foreach(catalogueItemRowsWithVariantRows) { catalogueItemRowWithVariantRows =>
            val catalogueItemRow = catalogueItemRowWithVariantRows.catalogueItemRow

            catalogueRepository
              .archiveCatalogueItemVariant(
                catalogueItemRow.organizationID,
                catalogueItemRow.catalogueItemID,
                catalogueItemRowWithVariantRows.catalogueItemVariantRow1.catalogueItemVariantID,
              )
              .either
              .zipPar(
                catalogueRepository
                  .archiveCatalogueItemVariant(
                    catalogueItemRow.organizationID,
                    catalogueItemRow.catalogueItemID,
                    catalogueItemRowWithVariantRows.catalogueItemVariantRow2.catalogueItemVariantID,
                  )
                  .either
              )
              .map((archiveResult1, archiveResult2) =>
                (
                  catalogueItemRowWithVariantRows = catalogueItemRowWithVariantRows,
                  archiveResult1 = archiveResult1,
                  archiveResult2 = archiveResult2,
                )
              )
          }
          .zioValue

        catalogueItemRowsWithVariantRowsAndArchiveResults.foreach { catalogueItemRowWithVariantRowsAndArchiveResults =>
          val catalogueItemRowWithVariantRows =
            catalogueItemRowWithVariantRowsAndArchiveResults.catalogueItemRowWithVariantRows
          val catalogueItemID = catalogueItemRowWithVariantRows.catalogueItemRow.catalogueItemID
          val invalidActionVariant1: Either[ServiceError, Unit] = Left(
            ServiceError.ForbiddenError.InvalidAction(
              "archiveCatalogueItemVariant",
              s"Catalogue item variant [${catalogueItemRowWithVariantRows.catalogueItemVariantRow1.catalogueItemVariantID}] is the last non-archived variant of catalogue item [$catalogueItemID]; archive the catalogue item instead",
            )
          )
          val invalidActionVariant2: Either[ServiceError, Unit] = Left(
            ServiceError.ForbiddenError.InvalidAction(
              "archiveCatalogueItemVariant",
              s"Catalogue item variant [${catalogueItemRowWithVariantRows.catalogueItemVariantRow2.catalogueItemVariantID}] is the last non-archived variant of catalogue item [$catalogueItemID]; archive the catalogue item instead",
            )
          )

          List(
            catalogueItemRowWithVariantRowsAndArchiveResults.archiveResult1,
            catalogueItemRowWithVariantRowsAndArchiveResults.archiveResult2,
          ) should (equal(List(Right(()), invalidActionVariant2)) or equal(List(invalidActionVariant1, Right(()))))
        }

        val catalogueItemVariantRowsExpected =
          catalogueItemRowsWithVariantRowsAndArchiveResults.flatMap {
            catalogueItemRowWithVariantRowsAndArchiveResults =>
              val catalogueItemRowWithVariantRows =
                catalogueItemRowWithVariantRowsAndArchiveResults.catalogueItemRowWithVariantRows
              val catalogueItemVariantRowsWinnerAndLoser =
                if (catalogueItemRowWithVariantRowsAndArchiveResults.archiveResult1.isRight)
                  (
                    winner = catalogueItemRowWithVariantRows.catalogueItemVariantRow1,
                    loser = catalogueItemRowWithVariantRows.catalogueItemVariantRow2,
                  )
                else
                  (
                    winner = catalogueItemRowWithVariantRows.catalogueItemVariantRow2,
                    loser = catalogueItemRowWithVariantRows.catalogueItemVariantRow1,
                  )
              List(
                catalogueItemVariantRowsWinnerAndLoser.winner.copy(
                  isArchived = true,
                  updatedAt = UpdatedAt(instantNow),
                ),
                catalogueItemVariantRowsWinnerAndLoser.loser,
              )
          }

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          catalogueItemRowsWithVariantRows.map(_.catalogueItemRow).toSet
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          catalogueItemVariantRowsExpected.toSet
      }

      "silently do nothing when the variant is already archived" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )

        Set(
          catalogueItemVariantRowNonArchived1.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived2.catalogueItemVariantID,
          catalogueItemVariantRowArchived.catalogueItemVariantID,
        ).size shouldBe 3
        catalogueItemVariantRowArchived.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowNonArchived1,
                catalogueItemVariantRowNonArchived2,
                catalogueItemVariantRowArchived,
              )
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRowArchived.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowNonArchived1, catalogueItemVariantRowNonArchived2, catalogueItemVariantRowArchived)
      }

      "silently do nothing when the variant does not exist" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantIDMissing = arbitrarySample[CatalogueItemVariantID]

        Set(
          catalogueItemVariantRow1.catalogueItemVariantID,
          catalogueItemVariantRow2.catalogueItemVariantID,
          catalogueItemVariantIDMissing,
        ).size shouldBe 3

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow1, catalogueItemVariantRow2))
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(organizationID, catalogueItemRow.catalogueItemID, catalogueItemVariantIDMissing)
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRow1, catalogueItemVariantRow2)
      }

      "silently do nothing when the variant belongs to another catalogue item" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowOther1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowOther2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          isArchived = false,
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        Set(
          catalogueItemVariantRow.catalogueItemVariantID,
          catalogueItemVariantRowOther1.catalogueItemVariantID,
          catalogueItemVariantRowOther2.catalogueItemVariantID,
        ).size shouldBe 3

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowOther)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowOther1, catalogueItemVariantRowOther2)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRowOther1.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemRow, catalogueItemRowOther)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRow, catalogueItemVariantRowOther1, catalogueItemVariantRowOther2)
      }

      "silently do nothing when the catalogue item belongs to another organization, leaving its variants untouched" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(organizationIDForeign)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )

        catalogueItemVariantRowForeign1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowForeign2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowForeign1, catalogueItemVariantRowForeign2)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRowForeign.catalogueItemID,
            catalogueItemVariantRowForeign1.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowForeign1, catalogueItemVariantRowForeign2)
      }

      "silently do nothing when the catalogue item is archived, leaving its variants untouched" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )

        catalogueItemVariantRowNonArchived1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowNonArchived2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived1, catalogueItemVariantRowNonArchived2)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRowArchived.catalogueItemID,
            catalogueItemVariantRowNonArchived1.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowArchived)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowNonArchived1, catalogueItemVariantRowNonArchived2)
      }
    }

    "getCatalogueItemWithVariants" should {
      "return an item with its non-archived variants, oldest created first" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(3)),
        )
        val catalogueItemVariantRowNonArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowNonArchived3 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          isArchived = false,
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        Set(
          catalogueItemVariantRowNonArchived1.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived2.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived3.catalogueItemVariantID,
          catalogueItemVariantRowArchived.catalogueItemVariantID,
          catalogueItemVariantRowOther.catalogueItemVariantID,
        ).size shouldBe 5

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowOther)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowNonArchived3,
                catalogueItemVariantRowNonArchived1,
                catalogueItemVariantRowArchived,
                catalogueItemVariantRowNonArchived2,
                catalogueItemVariantRowOther,
              )
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariants(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe Some(
          CatalogueItemWithVariantsRow(
            catalogueItemRow,
            NonEmptyChunk(
              catalogueItemVariantRowNonArchived1,
              catalogueItemVariantRowNonArchived2,
              catalogueItemVariantRowNonArchived3,
            ),
          )
        )
      }

      "return None when the catalogue item does not exist" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepository
          .getCatalogueItemWithVariants(organizationID, catalogueItemID)
          .zioValue shouldBe None
      }

      "return None when the catalogue item is archived, even though it has non-archived variants" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )

        catalogueItemVariantRowNonArchived1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowNonArchived2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived1, catalogueItemVariantRowNonArchived2)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariants(organizationID, catalogueItemRowArchived.catalogueItemID)
          .zioValue shouldBe None
      }

      "return None when the catalogue item belongs to another organization" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(organizationIDForeign)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )

        catalogueItemVariantRowForeign1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowForeign2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowForeign1, catalogueItemVariantRowForeign2)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariants(organizationID, catalogueItemRowForeign.catalogueItemID)
          .zioValue shouldBe None
      }

      "return None when the non-archived catalogue item has only archived variants" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = true,
        )

        catalogueItemVariantRowArchived1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowArchived2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowArchived1, catalogueItemVariantRowArchived2)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariants(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe None
      }
    }

    "getAllCatalogueItemSummaries" should {
      "return non-archived items with only non-archived variants, excluding archived records and other organizations" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]
        val catalogueItemRow1     = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived1a = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowNonArchived1b = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived2a = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowNonArchived2b = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowOfItemArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )

        val catalogueItemSummaryRowExpected1 = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          name = catalogueItemRow1.name,
          imageAsset = catalogueItemRow1.imageAsset,
          status = catalogueItemRow1.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived1a.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived1a.sku,
              label = catalogueItemVariantRowNonArchived1a.label,
              price = catalogueItemVariantRowNonArchived1a.price,
              sellingUnit = catalogueItemVariantRowNonArchived1a.sellingUnit,
              status = catalogueItemVariantRowNonArchived1a.status,
            ),
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived1b.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived1b.sku,
              label = catalogueItemVariantRowNonArchived1b.label,
              price = catalogueItemVariantRowNonArchived1b.price,
              sellingUnit = catalogueItemVariantRowNonArchived1b.sellingUnit,
              status = catalogueItemVariantRowNonArchived1b.status,
            ),
          ),
        )
        val catalogueItemSummaryRowExpected2 = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          name = catalogueItemRow2.name,
          imageAsset = catalogueItemRow2.imageAsset,
          status = catalogueItemRow2.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived2a.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived2a.sku,
              label = catalogueItemVariantRowNonArchived2a.label,
              price = catalogueItemVariantRowNonArchived2a.price,
              sellingUnit = catalogueItemVariantRowNonArchived2a.sellingUnit,
              status = catalogueItemVariantRowNonArchived2a.status,
            ),
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived2b.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived2b.sku,
              label = catalogueItemVariantRowNonArchived2b.label,
              price = catalogueItemVariantRowNonArchived2b.price,
              sellingUnit = catalogueItemVariantRowNonArchived2b.sellingUnit,
              status = catalogueItemVariantRowNonArchived2b.status,
            ),
          ),
        )

        organizationID shouldNot equal(organizationIDForeign)
        catalogueItemSummaryRowExpected1.name shouldNot equal(catalogueItemSummaryRowExpected2.name)
        Set(
          catalogueItemRow1.catalogueItemID,
          catalogueItemRow2.catalogueItemID,
          catalogueItemRowArchived.catalogueItemID,
          catalogueItemRowForeign.catalogueItemID,
        ).size shouldBe 4
        Set(
          catalogueItemVariantRowNonArchived1a.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived1b.catalogueItemVariantID,
          catalogueItemVariantRowArchived1.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived2a.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived2b.catalogueItemVariantID,
          catalogueItemVariantRowArchived2.catalogueItemVariantID,
          catalogueItemVariantRowOfItemArchived.catalogueItemVariantID,
          catalogueItemVariantRowForeign.catalogueItemVariantID,
        ).size shouldBe 8

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(
              List(catalogueItemRow2, catalogueItemRowArchived, catalogueItemRow1, catalogueItemRowForeign)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowNonArchived2b,
                catalogueItemVariantRowNonArchived1b,
                catalogueItemVariantRowArchived1,
                catalogueItemVariantRowOfItemArchived,
                catalogueItemVariantRowNonArchived2a,
                catalogueItemVariantRowArchived2,
                catalogueItemVariantRowNonArchived1a,
                catalogueItemVariantRowForeign,
              )
            )
          )
          .zioValue

        catalogueRepository.getAllCatalogueItemSummaries(organizationID).zioValue shouldBe
          List(catalogueItemSummaryRowExpected1, catalogueItemSummaryRowExpected2)
      }

      "return an empty list when the organization has no catalogue items" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        catalogueRepository.getAllCatalogueItemSummaries(organizationID).zioValue shouldBe Nil
      }

      "return an empty list when the organization's only catalogue items are archived, even though they have non-archived variants" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemRowArchived1 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemRowArchived2 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived1.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived2.catalogueItemID,
          isArchived = false,
        )

        catalogueItemRowArchived1.catalogueItemID shouldNot equal(catalogueItemRowArchived2.catalogueItemID)
        catalogueItemVariantRowNonArchived1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowNonArchived2.catalogueItemVariantID
        )

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(List(catalogueItemRowArchived1, catalogueItemRowArchived2))
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived1, catalogueItemVariantRowNonArchived2)
            )
          )
          .zioValue

        catalogueRepository.getAllCatalogueItemSummaries(organizationID).zioValue shouldBe Nil
      }

      "exclude a non-archived catalogue item whose variants are all archived" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemRowWithVariantsArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowWithVariantsArchived.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowWithVariantsArchived.catalogueItemID,
          isArchived = true,
        )

        val catalogueItemSummaryRowExpected = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemRow.name,
          imageAsset = catalogueItemRow.imageAsset,
          status = catalogueItemRow.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived.sku,
              label = catalogueItemVariantRowNonArchived.label,
              price = catalogueItemVariantRowNonArchived.price,
              sellingUnit = catalogueItemVariantRowNonArchived.sellingUnit,
              status = catalogueItemVariantRowNonArchived.status,
            )
          ),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowWithVariantsArchived.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowWithVariantsArchived.name)
        Set(
          catalogueItemVariantRowNonArchived.catalogueItemVariantID,
          catalogueItemVariantRowArchived1.catalogueItemVariantID,
          catalogueItemVariantRowArchived2.catalogueItemVariantID,
        ).size shouldBe 3

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowWithVariantsArchived))
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowNonArchived,
                catalogueItemVariantRowArchived1,
                catalogueItemVariantRowArchived2,
              )
            )
          )
          .zioValue

        catalogueRepository.getAllCatalogueItemSummaries(organizationID).zioValue shouldBe
          List(catalogueItemSummaryRowExpected)
      }
    }

    "getCatalogueItemSummaries" should {
      "return the requested non-archived catalogue items oldest created first regardless of request order, each with only its non-archived variants oldest created first, excluding archived variants and catalogue items not requested" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemRow1 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemRow2 = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemRowNotRequested = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived1a = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowNonArchived1b = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived2a = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowNonArchived2b = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          isArchived = false,
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowOfItemNotRequested = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowNotRequested.catalogueItemID,
          isArchived = false,
        )

        val catalogueItemSummaryRowExpected1 = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          name = catalogueItemRow1.name,
          imageAsset = catalogueItemRow1.imageAsset,
          status = catalogueItemRow1.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived1a.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived1a.sku,
              label = catalogueItemVariantRowNonArchived1a.label,
              price = catalogueItemVariantRowNonArchived1a.price,
              sellingUnit = catalogueItemVariantRowNonArchived1a.sellingUnit,
              status = catalogueItemVariantRowNonArchived1a.status,
            ),
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived1b.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived1b.sku,
              label = catalogueItemVariantRowNonArchived1b.label,
              price = catalogueItemVariantRowNonArchived1b.price,
              sellingUnit = catalogueItemVariantRowNonArchived1b.sellingUnit,
              status = catalogueItemVariantRowNonArchived1b.status,
            ),
          ),
        )
        val catalogueItemSummaryRowExpected2 = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          name = catalogueItemRow2.name,
          imageAsset = catalogueItemRow2.imageAsset,
          status = catalogueItemRow2.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived2a.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived2a.sku,
              label = catalogueItemVariantRowNonArchived2a.label,
              price = catalogueItemVariantRowNonArchived2a.price,
              sellingUnit = catalogueItemVariantRowNonArchived2a.sellingUnit,
              status = catalogueItemVariantRowNonArchived2a.status,
            ),
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived2b.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived2b.sku,
              label = catalogueItemVariantRowNonArchived2b.label,
              price = catalogueItemVariantRowNonArchived2b.price,
              sellingUnit = catalogueItemVariantRowNonArchived2b.sellingUnit,
              status = catalogueItemVariantRowNonArchived2b.status,
            ),
          ),
        )

        catalogueItemSummaryRowExpected1.name shouldNot equal(catalogueItemSummaryRowExpected2.name)
        Set(
          catalogueItemRow1.catalogueItemID,
          catalogueItemRow2.catalogueItemID,
          catalogueItemRowNotRequested.catalogueItemID,
        ).size shouldBe 3
        Set(
          catalogueItemVariantRowNonArchived1a.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived1b.catalogueItemVariantID,
          catalogueItemVariantRowArchived1.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived2a.catalogueItemVariantID,
          catalogueItemVariantRowNonArchived2b.catalogueItemVariantID,
          catalogueItemVariantRowArchived2.catalogueItemVariantID,
          catalogueItemVariantRowOfItemNotRequested.catalogueItemVariantID,
        ).size shouldBe 7

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(
              List(catalogueItemRow2, catalogueItemRowNotRequested, catalogueItemRow1)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowNonArchived2b,
                catalogueItemVariantRowNonArchived1b,
                catalogueItemVariantRowArchived1,
                catalogueItemVariantRowOfItemNotRequested,
                catalogueItemVariantRowNonArchived2a,
                catalogueItemVariantRowArchived2,
                catalogueItemVariantRowNonArchived1a,
              )
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemSummaries(
            organizationID,
            NonEmptyChunk(catalogueItemRow2.catalogueItemID, catalogueItemRow1.catalogueItemID),
          )
          .zioValue shouldBe List(catalogueItemSummaryRowExpected1, catalogueItemSummaryRowExpected2)
      }

      "exclude a requested catalogue item that is archived, even though it has non-archived variants" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowOfItemArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )

        val catalogueItemSummaryRowExpected = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemRow.name,
          imageAsset = catalogueItemRow.imageAsset,
          status = catalogueItemRow.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived.sku,
              label = catalogueItemVariantRowNonArchived.label,
              price = catalogueItemVariantRowNonArchived.price,
              sellingUnit = catalogueItemVariantRowNonArchived.sellingUnit,
              status = catalogueItemVariantRowNonArchived.status,
            )
          ),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowArchived.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowArchived.name)
        catalogueItemVariantRowNonArchived.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowOfItemArchived.catalogueItemVariantID
        )

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowArchived)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived, catalogueItemVariantRowOfItemArchived)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemSummaries(
            organizationID,
            NonEmptyChunk(catalogueItemRow.catalogueItemID, catalogueItemRowArchived.catalogueItemID),
          )
          .zioValue shouldBe List(catalogueItemSummaryRowExpected)
      }

      "exclude a requested catalogue item ID that does not exist or belongs to another organization" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val organizationIDForeign  = arbitrarySample[OrganizationID]
        val catalogueItemIDMissing = arbitrarySample[CatalogueItemID]
        val catalogueItemRow       = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          isArchived = false,
        )

        val catalogueItemSummaryRowExpected = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemRow.name,
          imageAsset = catalogueItemRow.imageAsset,
          status = catalogueItemRow.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived.sku,
              label = catalogueItemVariantRowNonArchived.label,
              price = catalogueItemVariantRowNonArchived.price,
              sellingUnit = catalogueItemVariantRowNonArchived.sellingUnit,
              status = catalogueItemVariantRowNonArchived.status,
            )
          ),
        )

        organizationID shouldNot equal(organizationIDForeign)
        Set(
          catalogueItemRow.catalogueItemID,
          catalogueItemRowForeign.catalogueItemID,
          catalogueItemIDMissing,
        ).size shouldBe 3
        catalogueItemVariantRowNonArchived.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowForeign.catalogueItemVariantID
        )

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowForeign)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowNonArchived, catalogueItemVariantRowForeign)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemSummaries(
            organizationID,
            NonEmptyChunk(
              catalogueItemRow.catalogueItemID,
              catalogueItemIDMissing,
              catalogueItemRowForeign.catalogueItemID,
            ),
          )
          .zioValue shouldBe List(catalogueItemSummaryRowExpected)
      }

      "exclude a requested non-archived catalogue item whose variants are all archived" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemRowWithVariantsArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRowNonArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val catalogueItemVariantRowArchived1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowWithVariantsArchived.catalogueItemID,
          isArchived = true,
        )
        val catalogueItemVariantRowArchived2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowWithVariantsArchived.catalogueItemID,
          isArchived = true,
        )

        val catalogueItemSummaryRowExpected = CatalogueItemSummaryRow(
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemRow.name,
          imageAsset = catalogueItemRow.imageAsset,
          status = catalogueItemRow.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowNonArchived.catalogueItemVariantID,
              sku = catalogueItemVariantRowNonArchived.sku,
              label = catalogueItemVariantRowNonArchived.label,
              price = catalogueItemVariantRowNonArchived.price,
              sellingUnit = catalogueItemVariantRowNonArchived.sellingUnit,
              status = catalogueItemVariantRowNonArchived.status,
            )
          ),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowWithVariantsArchived.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowWithVariantsArchived.name)
        Set(
          catalogueItemVariantRowNonArchived.catalogueItemVariantID,
          catalogueItemVariantRowArchived1.catalogueItemVariantID,
          catalogueItemVariantRowArchived2.catalogueItemVariantID,
        ).size shouldBe 3

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRows(List(catalogueItemRow, catalogueItemRowWithVariantsArchived))
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowNonArchived,
                catalogueItemVariantRowArchived1,
                catalogueItemVariantRowArchived2,
              )
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemSummaries(
            organizationID,
            NonEmptyChunk(catalogueItemRow.catalogueItemID, catalogueItemRowWithVariantsArchived.catalogueItemID),
          )
          .zioValue shouldBe List(catalogueItemSummaryRowExpected)
      }

      "return an empty list when none of the requested catalogue item IDs match a non-archived catalogue item" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemIDMissing   = arbitrarySample[CatalogueItemID]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = true,
        )
        val catalogueItemVariantRowOfItemArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )

        catalogueItemIDMissing shouldNot equal(catalogueItemRowArchived.catalogueItemID)

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRows(List(catalogueItemRowArchived)))
          .zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowOfItemArchived)))
          .zioValue

        catalogueRepository
          .getCatalogueItemSummaries(
            organizationID,
            NonEmptyChunk(catalogueItemRowArchived.catalogueItemID, catalogueItemIDMissing),
          )
          .zioValue shouldBe Nil
      }
    }

    "isUniqueCatalogueItemName" should {
      "return false if a non-archived catalogue item with the given name already exists" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          name = catalogueItemName,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe false
      }

      "return true if no catalogue item with the given name exists in the organization" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe true
      }

      "return true if the only catalogue item with the given name in the organization is archived" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          name = catalogueItemName,
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe true
      }
    }

    "isUniqueCatalogueItemVariantSKU" should {
      "return false when a non-archived variant has the same case-insensitive SKU" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemVariantSkuStored      = CatalogueItemVariantSku("lookup-sku")
        val catalogueItemVariantSkuCaseVariant = CatalogueItemVariantSku("LOOKUP-SKU")
        val catalogueItemRow                   = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSkuStored),
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        catalogueRepository
          .isUniqueCatalogueItemVariantSKU(organizationID, catalogueItemVariantSkuCaseVariant)
          .zioValue shouldBe false

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "return true if the catalogue item variant with the given SKU belongs to another organization" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val organizationIDForeign   = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationIDForeign,
          isArchived = false,
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          isArchived = false,
        )

        organizationID shouldNot equal(organizationIDForeign)

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign)))
          .zioValue

        catalogueRepository
          .isUniqueCatalogueItemVariantSKU(organizationID, catalogueItemVariantSku)
          .zioValue shouldBe true

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe
          List(catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowForeign)
      }

      "return true if the only catalogue item variant with the given SKU in the organization is archived" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        val catalogueItemRow                = arbitrarySample[CatalogueItemRow].copy(organizationID = organizationID)
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          isArchived = true,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchived)))
          .zioValue

        catalogueRepository
          .isUniqueCatalogueItemVariantSKU(organizationID, catalogueItemVariantSku)
          .zioValue shouldBe true
      }
    }
  }

  trait TestContext {
    val instantNow: Instant = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig: RepositoryConfig = RepositoryConfig(
      schema = "local_schema",
      catalogueItemTable = "catalogue_item",
      catalogueItemVariantTable = "catalogue_item_variant",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val catalogueQueries = ZIO
      .service[CatalogueQueries]
      .provide(CatalogueQueries.live, ZLayer.succeed(repositoryConfig))
      .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val catalogueRepository = ZIO
      .service[CatalogueRepository]
      .provide(
        CatalogueRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(catalogueQueries),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
      )
      .zioValue
  }
}
