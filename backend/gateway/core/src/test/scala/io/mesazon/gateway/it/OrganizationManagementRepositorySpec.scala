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
            organizationStage = organizationDetailsRow.organizationStage,
            addressLine1 = organizationDetailsRow.addressLine1,
            addressLine2 = organizationDetailsRow.addressLine2,
            city = organizationDetailsRow.city,
            postalCode = organizationDetailsRow.postalCode,
            country = organizationDetailsRow.country,
          )
          .zioValue

        val orgDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        orgDetailsRowsAll should have size 1
        orgDetailsRowsAll.head shouldBe organizationDetailsRow.copy(
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        val orgUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUserTesting).zioValue

        orgUserRowsAll should have size 1
        orgUserRowsAll.head shouldBe OrganizationUserRow(
          organizationID = organizationDetailsRow.organizationID,
          userID = userID,
          userRole = UserRole.Owner,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )
      }

      "fail to create an organization with a duplicate slug" in new TestContext {
        val existingOrgDetailsRow = arbitrarySample[OrganizationDetailsRow]

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(existingOrgDetailsRow))
          .zioValue

        val newOrgID = arbitrarySample[OrganizationID]
        val userID   = arbitrarySample[UserID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(newOrgID.value)
            .once(),
        )

        val serviceError = organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = existingOrgDetailsRow.name,
            slug = existingOrgDetailsRow.slug,
            organizationStage = existingOrgDetailsRow.organizationStage,
            addressLine1 = existingOrgDetailsRow.addressLine1,
            addressLine2 = existingOrgDetailsRow.addressLine2,
            city = existingOrgDetailsRow.city,
            postalCode = existingOrgDetailsRow.postalCode,
            country = existingOrgDetailsRow.country,
          )
          .zioError

        serviceError.message shouldBe s"Failed to create organization with ID: [$newOrgID]"
        serviceError.underlying.value shouldBe a[DbException]

        val orgDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        orgDetailsRowsAll should have size 1
        orgDetailsRowsAll.head shouldBe existingOrgDetailsRow

        val orgUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUserTesting).zioValue

        orgUserRowsAll shouldBe empty
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
