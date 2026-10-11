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
import org.scalacheck.{Arbitrary, Gen}
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
          status = arbitrarySample[CatalogueItemStatus](using Arbitrary(Gen.oneOf(CatalogueItemStatus.disabled))),
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
          status =
            arbitrarySample[CatalogueItemVariantStatus](using Arbitrary(Gen.oneOf(CatalogueItemVariantStatus.disabled))),
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

    "isUniqueCatalogueItemName" should {
      "return false if an active catalogue item with the given name already exists in the organization" in new TestContext {
        val organizationID    = arbitrarySample[OrganizationID]
        val catalogueItemName = arbitrarySample[CatalogueItemName]

        val catalogueItemRow = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          name = catalogueItemName,
          status = CatalogueItemStatus.Active,
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
          status = arbitrarySample[CatalogueItemStatus](using Arbitrary(Gen.oneOf(CatalogueItemStatus.disabled))),
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowDisabled)).zioValue

        catalogueRepository.isUniqueCatalogueItemName(organizationID, catalogueItemName).zioValue shouldBe true
      }
    }

    "isUniqueCatalogueItemVariantSKU" should {
      "return false if an enabled catalogue item variant with the given SKU already exists in the organization" in new TestContext {
        val organizationID          = arbitrarySample[OrganizationID]
        val catalogueItemVariantSku = arbitrarySample[CatalogueItemVariantSku]
        val enabledStatus           = arbitrarySample[CatalogueItemVariantStatus](using
          Arbitrary(Gen.oneOf(CatalogueItemVariantStatus.enabled))
        )

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
          status = arbitrarySample[CatalogueItemVariantStatus](using
            Arbitrary(Gen.oneOf(CatalogueItemVariantStatus.disabled))
          ),
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
