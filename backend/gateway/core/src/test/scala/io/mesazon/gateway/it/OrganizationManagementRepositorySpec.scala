package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.OrganizationManagementRepository
import io.mesazon.gateway.repository.domain.{OrganizationDetailsRow, OrganizationUserRow}
import io.mesazon.gateway.repository.queries.{OrganizationDetailsQueries, OrganizationUserQueries}
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class OrganizationManagementRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.organizationDetailsTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.organizationDetailsTable).zioValue
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.organizationUserTable).zioValue
    }
  }

  "OrganizationManagementRepository" when {
    "createOrganization" should {
      "successfully create a new organization with the owner user" in new TestContext {
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
        val userID                 = arbitrarySample[UserID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(organizationDetailsRow.organizationID.value)
            .once(),
        )

        organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow.name,
            slug = organizationDetailsRow.slug,
            email = organizationDetailsRow.email,
            phoneNumber = organizationDetailsRow.phoneNumber,
            organizationStage = organizationDetailsRow.organizationStage,
            addressLine1 = organizationDetailsRow.addressLine1,
            addressLine2 = organizationDetailsRow.addressLine2,
            city = organizationDetailsRow.city,
            postalCode = organizationDetailsRow.postalCode,
            country = organizationDetailsRow.country,
          )
          .zioValue

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 1
        organizationDetailsRowsAll.head shouldBe organizationDetailsRow.copy(
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUserTesting).zioValue

        organizationUserRowsAll should have size 1
        organizationUserRowsAll.head shouldBe OrganizationUserRow(
          organizationID = organizationDetailsRow.organizationID,
          userID = userID,
          userRole = UserRole.Owner,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
      }

      "successfully create a multiple organizations with the owner user being the same" in new TestContext {
        val instantNow1             = instantNow
        val instantNow2             = instantNow.plusSeconds(10)
        val organizationID1         = arbitrarySample[OrganizationID]
        val organizationID2         = arbitrarySample[OrganizationID]
        val organizationDetailsRow1 = arbitrarySample[OrganizationDetailsRow]
          .copy(
            organizationID = organizationID1,
            createdAt = CreatedAt(instantNow1),
            updatedAt = UpdatedAt(instantNow1),
          )
        val organizationDetailsRow2 = arbitrarySample[OrganizationDetailsRow]
          .copy(
            organizationID = organizationID2,
            createdAt = CreatedAt(instantNow2),
            updatedAt = UpdatedAt(instantNow2),
          )
        val userID = arbitrarySample[UserID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow1)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(organizationDetailsRow1.organizationID.value)
            .once(),
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow2)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(organizationDetailsRow2.organizationID.value)
            .once(),
        )

        organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow1.name,
            slug = organizationDetailsRow1.slug,
            email = organizationDetailsRow1.email,
            phoneNumber = organizationDetailsRow1.phoneNumber,
            organizationStage = organizationDetailsRow1.organizationStage,
            addressLine1 = organizationDetailsRow1.addressLine1,
            addressLine2 = organizationDetailsRow1.addressLine2,
            city = organizationDetailsRow1.city,
            postalCode = organizationDetailsRow1.postalCode,
            country = organizationDetailsRow1.country,
          )
          .zioValue

        organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow2.name,
            slug = organizationDetailsRow2.slug,
            email = organizationDetailsRow2.email,
            phoneNumber = organizationDetailsRow2.phoneNumber,
            organizationStage = organizationDetailsRow2.organizationStage,
            addressLine1 = organizationDetailsRow2.addressLine1,
            addressLine2 = organizationDetailsRow2.addressLine2,
            city = organizationDetailsRow2.city,
            postalCode = organizationDetailsRow2.postalCode,
            country = organizationDetailsRow2.country,
          )
          .zioValue

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 2
        organizationDetailsRowsAll should contain theSameElementsAs List(
          organizationDetailsRow1,
          organizationDetailsRow2,
        )

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUserTesting).zioValue

        organizationUserRowsAll should have size 2
        organizationUserRowsAll should contain theSameElementsAs List(
          OrganizationUserRow(
            organizationID = organizationDetailsRow1.organizationID,
            userID = userID,
            userRole = UserRole.Owner,
            createdAt = CreatedAt(instantNow1),
            updatedAt = UpdatedAt(instantNow1),
          ),
          OrganizationUserRow(
            organizationID = organizationDetailsRow2.organizationID,
            userID = userID,
            userRole = UserRole.Owner,
            createdAt = CreatedAt(instantNow2),
            updatedAt = UpdatedAt(instantNow2),
          ),
        )
      }

      "fail to create an organization with a duplicate slug" in new TestContext {
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))
          .zioValue

        val organizationIDNew = arbitrarySample[OrganizationID]
        val userID            = arbitrarySample[UserID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(organizationIDNew.value)
            .once(),
        )

        val serviceError = organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow.name,
            slug = organizationDetailsRow.slug,
            email = organizationDetailsRow.email,
            phoneNumber = organizationDetailsRow.phoneNumber,
            organizationStage = organizationDetailsRow.organizationStage,
            addressLine1 = organizationDetailsRow.addressLine1,
            addressLine2 = organizationDetailsRow.addressLine2,
            city = organizationDetailsRow.city,
            postalCode = organizationDetailsRow.postalCode,
            country = organizationDetailsRow.country,
          )
          .zioError

        serviceError.message shouldBe s"Failed to create organization with ID: [$organizationIDNew]"
        serviceError.underlying.value shouldBe a[DbException]

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 1
        organizationDetailsRowsAll.head shouldBe organizationDetailsRow

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUserTesting).zioValue

        organizationUserRowsAll shouldBe empty
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig = RepositoryConfig(
      schema = "local_schema",
      organizationDetailsTable = "organization_details",
      organizationUserTable = "organization_user",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val organizationDetailsQueries =
      ZIO
        .service[OrganizationDetailsQueries]
        .provide(OrganizationDetailsQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val organizationUserQueries =
      ZIO
        .service[OrganizationUserQueries]
        .provide(OrganizationUserQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val organizationManagementRepository = ZIO
      .service[OrganizationManagementRepository]
      .provide(
        OrganizationManagementRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(organizationDetailsQueries),
        ZLayer.succeed(organizationUserQueries),
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
      )
      .zioValue
  }
}
