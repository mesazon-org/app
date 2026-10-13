package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.CatalogueRepositoryV2
import io.mesazon.gateway.repository.CatalogueRepositoryV2.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.queries.CatalogueQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import org.scalacheck.Gen
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

// Temporary staging name; renamed to CatalogueRepositorySpec at cutover, alongside CatalogueRepositoryV2 itself.
class CatalogueRepositoryV2Spec extends ZWordSpecBase, CatalogueRepositoryV2Arbitraries, DockerComposeBase {

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

  "CatalogueRepositoryV2" when {
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

        val catalogueItemRowExpected = CatalogueItemRowV2(
          organizationID,
          catalogueItemID,
          insertCatalogueItemInput.name,
          insertCatalogueItemInput.description,
          None,
          CatalogueItemStatus.Active,
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
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
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemID

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
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
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
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

      "insert a catalogue item whose name matches a disabled catalogue item's name, because the partial unique index excludes disabled rows" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val catalogueItemIDEnabled = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID = arbitrarySample[CatalogueItemVariantID]
        val sharedName             = arbitrarySample[CatalogueItemName]

        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          name = sharedName,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue

        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]
        val insertCatalogueItemInput        = arbitrarySample[InsertCatalogueItemInput]
          .copy(name = sharedName, variants = NonEmptyChunk.single(insertCatalogueItemVariantInput))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemIDEnabled.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantID.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemIDEnabled

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue
          .map(_.catalogueItemID)
          .toSet shouldBe Set(catalogueItemRowDisabled.catalogueItemID, catalogueItemIDEnabled)
      }

      "insert a catalogue item variant whose SKU matches a disabled variant's SKU, because the partial unique index excludes disabled rows" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val catalogueItemIDDisabled        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantIDDisabled = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemIDEnabled         = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantIDEnabled  = arbitrarySample[CatalogueItemVariantID]
        val sharedSku                      = arbitrarySample[CatalogueItemVariantSku]

        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemIDDisabled,
        )
        val catalogueItemVariantRowDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemIDDisabled,
          catalogueItemVariantID = catalogueItemVariantIDDisabled,
          sku = Some(sharedSku),
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowDisabled)))
          .zioValue

        val insertCatalogueItemVariantInput = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(sharedSku))
        val insertCatalogueItemInput = arbitrarySample[InsertCatalogueItemInput]
          .copy(variants = NonEmptyChunk.single(insertCatalogueItemVariantInput))

        inSequence(
          (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemIDEnabled.value).once(),
          (() => idGeneratorMock.generateID).expects().returningZIO(catalogueItemVariantIDEnabled.value).once(),
        )

        catalogueRepository.insertCatalogueItem(organizationID, insertCatalogueItemInput).zioValue shouldBe
          catalogueItemIDEnabled

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue
          .map(_.catalogueItemVariantID)
          .toSet shouldBe Set(catalogueItemVariantIDDisabled, catalogueItemVariantIDEnabled)
      }

      "fail with a UniqueConstraintViolation when a variant SKU already exists in the organization, rolling back the second item" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemID1        = arbitrarySample[CatalogueItemID]
        val catalogueItemID2        = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1 = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2 = arbitrarySample[CatalogueItemVariantID]
        val sharedSku               = arbitrarySample[CatalogueItemVariantSku]

        catalogueItemID1 shouldNot equal(catalogueItemID2)

        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(sharedSku))
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(sharedSku))
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

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue
          .map(_.catalogueItemID) shouldBe
          List(catalogueItemID1)
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue
          .map(_.catalogueItemVariantID) shouldBe List(catalogueItemVariantID1)
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
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

        val catalogueItemRowV2Expected1 = CatalogueItemRowV2(
          organizationID,
          catalogueItemID1,
          insertCatalogueItemInput1.name,
          insertCatalogueItemInput1.description,
          None,
          CatalogueItemStatus.Active,
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
        )
        val catalogueItemRowV2Expected2 = catalogueItemRowV2Expected1.copy(
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
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowV2Expected1, catalogueItemRowV2Expected2)

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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with a UniqueConstraintViolation and roll back the whole batch when two items in the batch share a variant SKU" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val catalogueItemID1                 = arbitrarySample[CatalogueItemID]
        val catalogueItemID2                 = arbitrarySample[CatalogueItemID]
        val catalogueItemVariantID1          = arbitrarySample[CatalogueItemVariantID]
        val catalogueItemVariantID2          = arbitrarySample[CatalogueItemVariantID]
        val sharedSku                        = arbitrarySample[CatalogueItemVariantSku]
        val insertCatalogueItemVariantInput1 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(sharedSku))
        val insertCatalogueItemVariantInput2 = arbitrarySample[InsertCatalogueItemVariantInput]
          .copy(sku = Some(sharedSku))
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe Nil
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

        val catalogueItemRowExistingExpected = CatalogueItemRowV2(
          organizationID,
          catalogueItemIDExisting,
          insertCatalogueItemInputExisting.name,
          insertCatalogueItemInputExisting.description,
          None,
          CatalogueItemStatus.Active,
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
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
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowExistingExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowExistingExpected)
      }
    }

    "updateCatalogueItem" should {
      "update only the catalogue item's own fields when no variant changes are requested" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "update only the specified fields of a catalogue item variant, leaving the rest unchanged" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
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

      "update multiple catalogue item variants in one batched call, each keeping its own unspecified fields" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemVariantRow1.catalogueItemVariantID shouldNot equal(catalogueItemVariantRow2.catalogueItemVariantID)

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow1, catalogueItemVariantRow2))
          )
          .zioValue

        val skuUpdate   = arbitrarySample[CatalogueItemVariantSku]
        val labelUpdate = arbitrarySample[CatalogueItemVariantLabel]

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

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .updateCatalogueItem(
            organizationID,
            catalogueItemRow.catalogueItemID,
            updateCatalogueItemVariantInputs = List(updateCatalogueItemVariantInput1, updateCatalogueItemVariantInput2),
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowExpected1, catalogueItemVariantRowExpected2)
      }

      "insert new variants while also updating an existing variant, within the same transaction" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
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
          CreatedAt(instantNow),
          UpdatedAt(instantNow),
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
          val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
            organizationID = organizationID,
            status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            label = None,
            price = None,
            status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
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
            catalogueQueries.insertCatalogueItemRowsV2(
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "silently do nothing when the catalogue item is archived" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowArchived)).zioValue

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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowArchived)
      }

      "silently do nothing when the catalogue item belongs to another organization" in new TestContext {
        val organizationID      = arbitrarySample[OrganizationID]
        val otherOrganizationID = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(otherOrganizationID)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = otherOrganizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowForeign)).zioValue

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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowForeign)
      }

      "fail with an UnexpectedError and roll back the whole transaction when a variant ID does not exist" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue

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
          s"Failed to update catalogue item with ID: [${catalogueItemRow.catalogueItemID}]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are disabled. Requested: [${List(missingCatalogueItemVariantID)}], Found: [List()]"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "fail with an UnexpectedError and roll back the whole transaction when a variant belongs to another catalogue item" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemRowA = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemRowB = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )

        catalogueItemRowA.catalogueItemID shouldNot equal(catalogueItemRowB.catalogueItemID)

        val catalogueItemVariantRowB = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowB.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowA)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowB)).zioValue
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
          s"Failed to update catalogue item with ID: [${catalogueItemRowA.catalogueItemID}]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are disabled. Requested: [${List(catalogueItemVariantRowB.catalogueItemVariantID)}], Found: [List()]"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          Set(catalogueItemRowA, catalogueItemRowB)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowB)
      }

      "fail with an UnexpectedError and roll back the whole transaction when a variant is archived" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowArchived = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
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
          s"Failed to update catalogue item with ID: [${catalogueItemRow.catalogueItemID}]. Some catalogue item variant IDs do not exist, belong to another catalogue item, or are disabled. Requested: [${List(catalogueItemVariantRowArchived.catalogueItemVariantID)}], Found: [List()]"
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowArchived)
      }

      "fail with a UniqueConstraintViolation and roll back the whole transaction when a new variant's SKU already exists" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val sharedSku               = arbitrarySample[CatalogueItemVariantSku]
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(sharedSku),
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRow)
      }

      "fail with a UniqueConstraintViolation and roll back the whole transaction when the new name matches another active catalogue item's name" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemRowA = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemRowB = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )

        catalogueItemRowA.catalogueItemID shouldNot equal(catalogueItemRowB.catalogueItemID)
        catalogueItemRowA.name shouldNot equal(catalogueItemRowB.name)

        val catalogueItemVariantRowA = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowA.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowB = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowB.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowA)).zioValue
        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowB)).zioValue
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          Set(catalogueItemRowA, catalogueItemRowB)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowA, catalogueItemVariantRowB)
      }
    }

    "archiveCatalogueItem" should {
      "archive the catalogue item and every enabled variant of it, leaving other catalogue items untouched" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        catalogueItemVariantRow1.catalogueItemVariantID shouldNot equal(catalogueItemVariantRow2.catalogueItemVariantID)
        catalogueItemRow.updatedAt shouldNot equal(UpdatedAt(instantNow))
        catalogueItemVariantRow1.updatedAt shouldNot equal(UpdatedAt(instantNow))
        catalogueItemVariantRow2.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRowsV2(List(catalogueItemRow, catalogueItemRowOther)))
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
          status = CatalogueItemStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected1 = catalogueItemVariantRow1.copy(
          status = CatalogueItemVariantStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowExpected2 = catalogueItemVariantRow2.copy(
          status = CatalogueItemVariantStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          Set(catalogueItemRowExpected, catalogueItemRowOther)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowExpected1, catalogueItemVariantRowExpected2, catalogueItemVariantRowOther)
      }

      "leave an already-archived variant of the catalogue item untouched, including its updatedAt" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        catalogueItemVariantRowEnabled.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowDisabled.catalogueItemVariantID
        )
        catalogueItemVariantRowDisabled.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled, catalogueItemVariantRowDisabled)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe (())

        val catalogueItemRowExpected = catalogueItemRow.copy(
          status = CatalogueItemStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )
        val catalogueItemVariantRowEnabledExpected = catalogueItemVariantRowEnabled.copy(
          status = CatalogueItemVariantStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowExpected)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowEnabledExpected, catalogueItemVariantRowDisabled)
      }

      "silently do nothing when the catalogue item is already archived, leaving its variants untouched" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )
        val catalogueItemVariantRowEnabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowEnabled)))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRowDisabled.catalogueItemID)
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowDisabled)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowEnabled)
      }

      "silently do nothing when the catalogue item does not exist" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository.archiveCatalogueItem(organizationID, catalogueItemID).zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe Nil
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe Nil
      }

      "silently do nothing when the catalogue item belongs to another organization, leaving its variants untouched" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(organizationIDForeign)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationIDForeign,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowForeign)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign)))
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItem(organizationID, catalogueItemRowForeign.catalogueItemID)
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe
          List(catalogueItemVariantRowForeign)
      }
    }

    "archiveCatalogueItem and updateCatalogueItem" should {
      "never leave an enabled variant on an archived catalogue item when an archive and a variant-adding update run concurrently" in new TestContext {
        val iterations = 50

        val catalogueItemRowsWithVariantRowAndInsertInput = List.fill(iterations) {
          val organizationID   = arbitrarySample[OrganizationID]
          val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
            organizationID = organizationID,
            status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
          )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
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
            catalogueQueries.insertCatalogueItemRowsV2(
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
              status = CatalogueItemStatus.Archived,
              updatedAt = UpdatedAt(instantNow),
            )
          )
        val catalogueItemVariantRowsExistingExpected =
          catalogueItemRowsWithVariantRowAndInsertInput.map(catalogueItemRowWithVariantRowAndInsertInput =>
            catalogueItemRowWithVariantRowAndInsertInput.catalogueItemVariantRow.copy(
              status = CatalogueItemVariantStatus.Archived,
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          catalogueItemRowsExpected.toSet
        catalogueItemVariantRows
          .filter(catalogueItemVariantRow =>
            catalogueItemVariantIDsExisting.contains(catalogueItemVariantRow.catalogueItemVariantID)
          )
          .toSet shouldBe catalogueItemVariantRowsExistingExpected.toSet
        every(catalogueItemVariantRowsAdded.map(_.status)) shouldBe CatalogueItemVariantStatus.Archived
      }
    }

    "archiveCatalogueItemVariant" should {
      "archive only the given variant, leaving its catalogue item, its other variants, and other catalogue items untouched" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRow3 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
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
          .executeQuery(catalogueQueries.insertCatalogueItemRowsV2(List(catalogueItemRow, catalogueItemRowOther)))
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
          status = CatalogueItemVariantStatus.Archived,
          updatedAt = UpdatedAt(instantNow),
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          Set(catalogueItemRow, catalogueItemRowOther)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(
            catalogueItemVariantRowExpected1,
            catalogueItemVariantRow2,
            catalogueItemVariantRow3,
            catalogueItemVariantRowOther,
          )
      }

      "fail with InvalidAction and change nothing when the variant is the last enabled variant of its catalogue item" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        catalogueItemVariantRowEnabled.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowDisabled.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled, catalogueItemVariantRowDisabled)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRowEnabled.catalogueItemVariantID,
          )
          .zioError shouldBe ServiceError.ForbiddenError.InvalidAction(
          "archiveCatalogueItemVariant",
          s"Catalogue item variant [${catalogueItemVariantRowEnabled.catalogueItemVariantID}] is the last enabled variant of catalogue item [${catalogueItemRow.catalogueItemID}]; archive the catalogue item instead",
        )

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowEnabled, catalogueItemVariantRowDisabled)
      }

      "allow exactly one of two concurrent archives of a catalogue item's last two enabled variants, rejecting the other with InvalidAction" in new TestContext {
        // Each archive counts the item's enabled variants, then archives a different row. Without the item row lock
        // serializing the two transactions, both see two enabled variants and both succeed, leaving none enabled.
        val iterations = 50

        val catalogueItemRowsWithVariantRows = List.fill(iterations) {
          val organizationID   = arbitrarySample[OrganizationID]
          val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
            organizationID = organizationID,
            status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
          )
          (
            catalogueItemRow = catalogueItemRow,
            catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
            ),
            catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
            ),
          )
        }

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRowsV2(catalogueItemRowsWithVariantRows.map(_.catalogueItemRow))
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
              s"Catalogue item variant [${catalogueItemRowWithVariantRows.catalogueItemVariantRow1.catalogueItemVariantID}] is the last enabled variant of catalogue item [$catalogueItemID]; archive the catalogue item instead",
            )
          )
          val invalidActionVariant2: Either[ServiceError, Unit] = Left(
            ServiceError.ForbiddenError.InvalidAction(
              "archiveCatalogueItemVariant",
              s"Catalogue item variant [${catalogueItemRowWithVariantRows.catalogueItemVariantRow2.catalogueItemVariantID}] is the last enabled variant of catalogue item [$catalogueItemID]; archive the catalogue item instead",
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
                  status = CatalogueItemVariantStatus.Archived,
                  updatedAt = UpdatedAt(instantNow),
                ),
                catalogueItemVariantRowsWinnerAndLoser.loser,
              )
          }

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          catalogueItemRowsWithVariantRows.map(_.catalogueItemRow).toSet
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          catalogueItemVariantRowsExpected.toSet
      }

      "silently do nothing when the variant is already archived" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        Set(
          catalogueItemVariantRowEnabled1.catalogueItemVariantID,
          catalogueItemVariantRowEnabled2.catalogueItemVariantID,
          catalogueItemVariantRowDisabled.catalogueItemVariantID,
        ).size shouldBe 3
        catalogueItemVariantRowDisabled.updatedAt shouldNot equal(UpdatedAt(instantNow))

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled1, catalogueItemVariantRowEnabled2, catalogueItemVariantRowDisabled)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRow.catalogueItemID,
            catalogueItemVariantRowDisabled.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowEnabled1, catalogueItemVariantRowEnabled2, catalogueItemVariantRowDisabled)
      }

      "silently do nothing when the variant does not exist" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRow2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantIDMissing = arbitrarySample[CatalogueItemVariantID]

        Set(
          catalogueItemVariantRow1.catalogueItemVariantID,
          catalogueItemVariantRow2.catalogueItemVariantID,
          catalogueItemVariantIDMissing,
        ).size shouldBe 3

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow1, catalogueItemVariantRow2))
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(organizationID, catalogueItemRow.catalogueItemID, catalogueItemVariantIDMissing)
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRow)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRow1, catalogueItemVariantRow2)
      }

      "silently do nothing when the variant belongs to another catalogue item" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowOther1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowOther2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        Set(
          catalogueItemVariantRow.catalogueItemVariantID,
          catalogueItemVariantRowOther1.catalogueItemVariantID,
          catalogueItemVariantRowOther2.catalogueItemVariantID,
        ).size shouldBe 3

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRowsV2(List(catalogueItemRow, catalogueItemRowOther)))
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue.toSet shouldBe
          Set(catalogueItemRow, catalogueItemRowOther)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRow, catalogueItemVariantRowOther1, catalogueItemVariantRowOther2)
      }

      "silently do nothing when the catalogue item belongs to another organization, leaving its variants untouched" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(organizationIDForeign)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationIDForeign,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowForeign1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowForeign2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemVariantRowForeign1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowForeign2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowForeign)).zioValue
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

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowForeign)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowForeign1, catalogueItemVariantRowForeign2)
      }

      "silently do nothing when the catalogue item is archived, leaving its variants untouched" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )
        val catalogueItemVariantRowEnabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemVariantRowEnabled1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowEnabled2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled1, catalogueItemVariantRowEnabled2)
            )
          )
          .zioValue

        (() => timeProviderMock.instantNow).expects().returningZIO(instantNow).once()

        catalogueRepository
          .archiveCatalogueItemVariant(
            organizationID,
            catalogueItemRowDisabled.catalogueItemID,
            catalogueItemVariantRowEnabled1.catalogueItemVariantID,
          )
          .zioValue shouldBe (())

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing).zioValue shouldBe
          List(catalogueItemRowDisabled)
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue.toSet shouldBe
          Set(catalogueItemVariantRowEnabled1, catalogueItemVariantRowEnabled2)
      }
    }

    "getCatalogueItemWithVariantsActive" should {
      "return the active catalogue item with only its enabled variants, oldest created first, excluding its archived variants and other catalogue items' variants" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(3)),
        )
        val catalogueItemVariantRowEnabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowEnabled3 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )
        val catalogueItemRowOther = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowOther = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowOther.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowOther.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowOther.name)
        Set(
          catalogueItemVariantRowEnabled1.catalogueItemVariantID,
          catalogueItemVariantRowEnabled2.catalogueItemVariantID,
          catalogueItemVariantRowEnabled3.catalogueItemVariantID,
          catalogueItemVariantRowDisabled.catalogueItemVariantID,
          catalogueItemVariantRowOther.catalogueItemVariantID,
        ).size shouldBe 5

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRowsV2(List(catalogueItemRow, catalogueItemRowOther)))
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowEnabled3,
                catalogueItemVariantRowEnabled1,
                catalogueItemVariantRowDisabled,
                catalogueItemVariantRowEnabled2,
                catalogueItemVariantRowOther,
              )
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariantsActive(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe Some(
          CatalogueItemWithVariantsRow(
            catalogueItemRow,
            NonEmptyChunk(
              catalogueItemVariantRowEnabled1,
              catalogueItemVariantRowEnabled2,
              catalogueItemVariantRowEnabled3,
            ),
          )
        )
      }

      "return None when the catalogue item does not exist" in new TestContext {
        val organizationID  = arbitrarySample[OrganizationID]
        val catalogueItemID = arbitrarySample[CatalogueItemID]

        catalogueRepository
          .getCatalogueItemWithVariantsActive(organizationID, catalogueItemID)
          .zioValue shouldBe None
      }

      "return None when the catalogue item is archived, even though it has enabled variants" in new TestContext {
        val organizationID           = arbitrarySample[OrganizationID]
        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )
        val catalogueItemVariantRowEnabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemVariantRowEnabled1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowEnabled2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled1, catalogueItemVariantRowEnabled2)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariantsActive(organizationID, catalogueItemRowDisabled.catalogueItemID)
          .zioValue shouldBe None
      }

      "return None when the catalogue item belongs to another organization" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]

        organizationID shouldNot equal(organizationIDForeign)

        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationIDForeign,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowForeign1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowForeign2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemVariantRowForeign1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowForeign2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowForeign)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowForeign1, catalogueItemVariantRowForeign2)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariantsActive(organizationID, catalogueItemRowForeign.catalogueItemID)
          .zioValue shouldBe None
      }

      "return None when the active catalogue item has only archived variants" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowDisabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )
        val catalogueItemVariantRowDisabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        catalogueItemVariantRowDisabled1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowDisabled2.catalogueItemVariantID
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowDisabled1, catalogueItemVariantRowDisabled2)
            )
          )
          .zioValue

        catalogueRepository
          .getCatalogueItemWithVariantsActive(organizationID, catalogueItemRow.catalogueItemID)
          .zioValue shouldBe None
      }
    }

    "getAllCatalogueItemSummariesEnabled" should {
      "return every enabled catalogue item in the organization oldest created first, each with only its enabled variants oldest created first, excluding disabled catalogue items, disabled variants, and other organizations' catalogue items" in new TestContext {
        val organizationID        = arbitrarySample[OrganizationID]
        val organizationIDForeign = arbitrarySample[OrganizationID]
        val catalogueItemRow1     = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemRow2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )
        val catalogueItemRowForeign = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationIDForeign,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled1a = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowEnabled1b = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowDisabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )
        val catalogueItemVariantRowEnabled2a = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(2)),
        )
        val catalogueItemVariantRowEnabled2b = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
          createdAt = CreatedAt(instantNow.minusSeconds(1)),
        )
        val catalogueItemVariantRowDisabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )
        val catalogueItemVariantRowOfItemDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationIDForeign,
          catalogueItemID = catalogueItemRowForeign.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        val catalogueItemSummaryRowV2Expected1 = CatalogueItemSummaryRowV2(
          catalogueItemID = catalogueItemRow1.catalogueItemID,
          name = catalogueItemRow1.name,
          imageAsset = catalogueItemRow1.imageAsset,
          status = catalogueItemRow1.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowEnabled1a.catalogueItemVariantID,
              sku = catalogueItemVariantRowEnabled1a.sku,
              label = catalogueItemVariantRowEnabled1a.label,
              price = catalogueItemVariantRowEnabled1a.price,
              sellingUnit = catalogueItemVariantRowEnabled1a.sellingUnit,
              status = catalogueItemVariantRowEnabled1a.status,
            ),
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowEnabled1b.catalogueItemVariantID,
              sku = catalogueItemVariantRowEnabled1b.sku,
              label = catalogueItemVariantRowEnabled1b.label,
              price = catalogueItemVariantRowEnabled1b.price,
              sellingUnit = catalogueItemVariantRowEnabled1b.sellingUnit,
              status = catalogueItemVariantRowEnabled1b.status,
            ),
          ),
        )
        val catalogueItemSummaryRowV2Expected2 = CatalogueItemSummaryRowV2(
          catalogueItemID = catalogueItemRow2.catalogueItemID,
          name = catalogueItemRow2.name,
          imageAsset = catalogueItemRow2.imageAsset,
          status = catalogueItemRow2.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowEnabled2a.catalogueItemVariantID,
              sku = catalogueItemVariantRowEnabled2a.sku,
              label = catalogueItemVariantRowEnabled2a.label,
              price = catalogueItemVariantRowEnabled2a.price,
              sellingUnit = catalogueItemVariantRowEnabled2a.sellingUnit,
              status = catalogueItemVariantRowEnabled2a.status,
            ),
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowEnabled2b.catalogueItemVariantID,
              sku = catalogueItemVariantRowEnabled2b.sku,
              label = catalogueItemVariantRowEnabled2b.label,
              price = catalogueItemVariantRowEnabled2b.price,
              sellingUnit = catalogueItemVariantRowEnabled2b.sellingUnit,
              status = catalogueItemVariantRowEnabled2b.status,
            ),
          ),
        )

        organizationID shouldNot equal(organizationIDForeign)
        catalogueItemSummaryRowV2Expected1.name shouldNot equal(catalogueItemSummaryRowV2Expected2.name)
        Set(
          catalogueItemRow1.catalogueItemID,
          catalogueItemRow2.catalogueItemID,
          catalogueItemRowDisabled.catalogueItemID,
          catalogueItemRowForeign.catalogueItemID,
        ).size shouldBe 4
        Set(
          catalogueItemVariantRowEnabled1a.catalogueItemVariantID,
          catalogueItemVariantRowEnabled1b.catalogueItemVariantID,
          catalogueItemVariantRowDisabled1.catalogueItemVariantID,
          catalogueItemVariantRowEnabled2a.catalogueItemVariantID,
          catalogueItemVariantRowEnabled2b.catalogueItemVariantID,
          catalogueItemVariantRowDisabled2.catalogueItemVariantID,
          catalogueItemVariantRowOfItemDisabled.catalogueItemVariantID,
          catalogueItemVariantRowForeign.catalogueItemVariantID,
        ).size shouldBe 8

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRowsV2(
              List(catalogueItemRow2, catalogueItemRowDisabled, catalogueItemRow1, catalogueItemRowForeign)
            )
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(
                catalogueItemVariantRowEnabled2b,
                catalogueItemVariantRowEnabled1b,
                catalogueItemVariantRowDisabled1,
                catalogueItemVariantRowOfItemDisabled,
                catalogueItemVariantRowEnabled2a,
                catalogueItemVariantRowDisabled2,
                catalogueItemVariantRowEnabled1a,
                catalogueItemVariantRowForeign,
              )
            )
          )
          .zioValue

        catalogueRepository.getAllCatalogueItemSummariesEnabled(organizationID).zioValue shouldBe
          List(catalogueItemSummaryRowV2Expected1, catalogueItemSummaryRowV2Expected2)
      }

      "return an empty list when the organization has no catalogue items" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        catalogueRepository.getAllCatalogueItemSummariesEnabled(organizationID).zioValue shouldBe Nil
      }

      "return an empty list when the organization's only catalogue items are disabled, even though they have enabled variants" in new TestContext {
        val organizationID            = arbitrarySample[OrganizationID]
        val catalogueItemRowDisabled1 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )
        val catalogueItemRowDisabled2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )
        val catalogueItemVariantRowEnabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled1.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowDisabled2.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )

        catalogueItemRowDisabled1.catalogueItemID shouldNot equal(catalogueItemRowDisabled2.catalogueItemID)
        catalogueItemVariantRowEnabled1.catalogueItemVariantID shouldNot equal(
          catalogueItemVariantRowEnabled2.catalogueItemVariantID
        )

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRowsV2(List(catalogueItemRowDisabled1, catalogueItemRowDisabled2))
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled1, catalogueItemVariantRowEnabled2)
            )
          )
          .zioValue

        catalogueRepository.getAllCatalogueItemSummariesEnabled(organizationID).zioValue shouldBe Nil
      }

      "exclude an enabled catalogue item whose variants are all disabled" in new TestContext {
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemRowWithVariantsDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )
        val catalogueItemVariantRowEnabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled)),
        )
        val catalogueItemVariantRowDisabled1 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowWithVariantsDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )
        val catalogueItemVariantRowDisabled2 = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRowWithVariantsDisabled.catalogueItemID,
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        val catalogueItemSummaryRowV2Expected = CatalogueItemSummaryRowV2(
          catalogueItemID = catalogueItemRow.catalogueItemID,
          name = catalogueItemRow.name,
          imageAsset = catalogueItemRow.imageAsset,
          status = catalogueItemRow.status,
          catalogueItemVariantSummaryRows = NonEmptyChunk(
            CatalogueItemVariantSummaryRow(
              catalogueItemVariantID = catalogueItemVariantRowEnabled.catalogueItemVariantID,
              sku = catalogueItemVariantRowEnabled.sku,
              label = catalogueItemVariantRowEnabled.label,
              price = catalogueItemVariantRowEnabled.price,
              sellingUnit = catalogueItemVariantRowEnabled.sellingUnit,
              status = catalogueItemVariantRowEnabled.status,
            )
          ),
        )

        catalogueItemRow.catalogueItemID shouldNot equal(catalogueItemRowWithVariantsDisabled.catalogueItemID)
        catalogueItemRow.name shouldNot equal(catalogueItemRowWithVariantsDisabled.name)
        Set(
          catalogueItemVariantRowEnabled.catalogueItemVariantID,
          catalogueItemVariantRowDisabled1.catalogueItemVariantID,
          catalogueItemVariantRowDisabled2.catalogueItemVariantID,
        ).size shouldBe 3

        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemRowsV2(List(catalogueItemRow, catalogueItemRowWithVariantsDisabled))
          )
          .zioValue
        postgresClient
          .executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRowEnabled, catalogueItemVariantRowDisabled1, catalogueItemVariantRowDisabled2)
            )
          )
          .zioValue

        catalogueRepository.getAllCatalogueItemSummariesEnabled(organizationID).zioValue shouldBe
          List(catalogueItemSummaryRowV2Expected)
      }
    }

    "isUniqueCatalogueItemName" should {
      "return false if an active catalogue item with the given name already exists in the organization" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          name = catalogueItemName,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.enabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe false
      }

      "return true if no catalogue item with the given name exists in the organization" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe true
      }

      "return true if the only catalogue item with the given name in the organization is disabled" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        val catalogueItemRowDisabled = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          name = catalogueItemName,
          status = arbitrarySample(Gen.oneOf(CatalogueItemStatus.disabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe true
      }
    }

    "isUniqueCatalogueItemVariantSKU" should {
      "return false if an enabled catalogue item variant with the given SKU already exists in the organization" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
        val enabledStatus           = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.enabled))

        val catalogueItemRow        = arbitrarySample[CatalogueItemRowV2].copy(organizationID = organizationID)
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          status = enabledStatus,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        catalogueRepository
          .isUniqueCatalogueItemVariantSKU(organizationID, catalogueItemVariantSku)
          .zioValue shouldBe false
      }

      "return true if no catalogue item variant with the given SKU exists in the organization" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        catalogueRepository
          .isUniqueCatalogueItemVariantSKU(organizationID, catalogueItemVariantSku)
          .zioValue shouldBe true
      }

      "return true if the only catalogue item variant with the given SKU in the organization is disabled" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]

        val catalogueItemRow                = arbitrarySample[CatalogueItemRowV2].copy(organizationID = organizationID)
        val catalogueItemVariantRowDisabled = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          sku = Some(catalogueItemVariantSku),
          status = arbitrarySample(Gen.oneOf(CatalogueItemVariantStatus.disabled)),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowDisabled)))
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
      .service[CatalogueRepositoryV2]
      .provide(
        CatalogueRepositoryV2.live,
        postgresClient.databaseLive,
        ZLayer.succeed(catalogueQueries),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
      )
      .zioValue
  }
}
