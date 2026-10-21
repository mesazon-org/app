package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.OrganizationManagementRepository
import io.mesazon.gateway.repository.domain.{OrganizationAndUserDetailsRow, OrganizationDetailsRow, OrganizationUserRow}
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

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

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
    "getOrganization" should {
      "successfully get the organization details by organization ID" in new TestContext {
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))
          .zioValue

        val organizationDetailsRowGet = organizationManagementRepository
          .getOrganization(organizationDetailsRow.organizationID)
          .zioValue

        organizationDetailsRowGet shouldBe Some(organizationDetailsRow)
      }

      "return None if the organization does not exist" in new TestContext {
        val organizationID = arbitrarySample[OrganizationID]

        val organizationDetailsRowGet = organizationManagementRepository
          .getOrganization(organizationID)
          .zioValue

        organizationDetailsRowGet shouldBe None
      }
    }

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

        val organizationDetailsRowInsert = organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow.name,
            slug = organizationDetailsRow.slug,
            tagline = organizationDetailsRow.tagline,
            emails = organizationDetailsRow.emails,
            phoneNumbers = organizationDetailsRow.phoneNumbers,
            organizationStage = organizationDetailsRow.organizationStage,
            addresses = organizationDetailsRow.addresses,
            companyRegistrationNumber = organizationDetailsRow.companyRegistrationNumber,
            taxID = organizationDetailsRow.taxID,
          )
          .zioValue

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 1
        organizationDetailsRowsAll.head shouldBe organizationDetailsRowInsert
        organizationDetailsRowsAll.head shouldBe organizationDetailsRow.copy(
          logoImageAsset = None,
          createdAt = CreatedAt(instantNow),
          updatedAt = UpdatedAt(instantNow),
        )

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting).zioValue

        organizationUserRowsAll should have size 1
        organizationUserRowsAll.head shouldBe OrganizationUserRow(
          organizationID = organizationDetailsRow.organizationID,
          userID = userID,
          userRole = OrganizationUserRole.Owner,
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

        val organizationDetailsRowInsert1 = organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow1.name,
            slug = organizationDetailsRow1.slug,
            tagline = organizationDetailsRow1.tagline,
            emails = organizationDetailsRow1.emails,
            phoneNumbers = organizationDetailsRow1.phoneNumbers,
            organizationStage = organizationDetailsRow1.organizationStage,
            addresses = organizationDetailsRow1.addresses,
            companyRegistrationNumber = organizationDetailsRow1.companyRegistrationNumber,
            taxID = organizationDetailsRow1.taxID,
          )
          .zioValue

        val organizationDetailsRowInsert2 = organizationManagementRepository
          .createOrganization(
            userID = userID,
            name = organizationDetailsRow2.name,
            slug = organizationDetailsRow2.slug,
            tagline = organizationDetailsRow2.tagline,
            emails = organizationDetailsRow2.emails,
            phoneNumbers = organizationDetailsRow2.phoneNumbers,
            organizationStage = organizationDetailsRow2.organizationStage,
            addresses = organizationDetailsRow2.addresses,
            companyRegistrationNumber = organizationDetailsRow2.companyRegistrationNumber,
            taxID = organizationDetailsRow2.taxID,
          )
          .zioValue

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 2
        organizationDetailsRowsAll should contain theSameElementsAs List(
          organizationDetailsRowInsert1,
          organizationDetailsRowInsert2,
        )
        organizationDetailsRowsAll should contain theSameElementsAs List(
          organizationDetailsRow1.copy(
            logoImageAsset = None
          ),
          organizationDetailsRow2.copy(
            logoImageAsset = None
          ),
        )

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting).zioValue

        organizationUserRowsAll should have size 2
        organizationUserRowsAll should contain theSameElementsAs List(
          OrganizationUserRow(
            organizationID = organizationDetailsRow1.organizationID,
            userID = userID,
            userRole = OrganizationUserRole.Owner,
            createdAt = CreatedAt(instantNow1),
            updatedAt = UpdatedAt(instantNow1),
          ),
          OrganizationUserRow(
            organizationID = organizationDetailsRow2.organizationID,
            userID = userID,
            userRole = OrganizationUserRole.Owner,
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
            tagline = organizationDetailsRow.tagline,
            emails = organizationDetailsRow.emails,
            phoneNumbers = organizationDetailsRow.phoneNumbers,
            organizationStage = organizationDetailsRow.organizationStage,
            addresses = organizationDetailsRow.addresses,
            companyRegistrationNumber = organizationDetailsRow.companyRegistrationNumber,
            taxID = organizationDetailsRow.taxID,
          )
          .zioError

        serviceError.message shouldBe s"Failed to create organization with ID: [$organizationIDNew]"
        serviceError.underlying.value shouldBe a[DbException]

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 1
        organizationDetailsRowsAll.head shouldBe organizationDetailsRow

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting).zioValue

        organizationUserRowsAll shouldBe empty
      }
    }

    "updateOrganizationDetails" should {
      "successfully update the organization details" in new TestContext {
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))
          .zioValue

        val nameOptUpdate                      = arbitrarySample[Option[OrganizationName]]
        val slugOptUpdate                      = arbitrarySample[Option[OrganizationSlug]]
        val taglineOptUpdate                   = arbitrarySample[Option[OrganizationTagline]]
        val emailsOptUpdate                    = arbitrarySample[Option[List[OrganizationEmailEntryRequest]]]
        val phoneNumbersOptUpdate              = arbitrarySample[Option[List[OrganizationPhoneNumberEntryRequest]]]
        val organizationStageOptUpdate         = arbitrarySample[Option[OrganizationStage]]
        val addressesOptUpdate                 = arbitrarySample[Option[List[OrganizationAddressEntry]]]
        val companyRegistrationNumberOptUpdate = arbitrarySample[Option[OrganizationCompanyRegistrationNumber]]
        val taxIDOptUpdate                     = arbitrarySample[Option[OrganizationTaxID]]
        val logoImageAssetOptUpdate            = arbitrarySample[Option[OrganizationLogoImageAsset]]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val organizationDetailsRowUpdated = organizationManagementRepository
          .updateOrganization(
            organizationID = organizationDetailsRow.organizationID,
            organizationStageOptUpdate = organizationStageOptUpdate,
            nameOptUpdate = nameOptUpdate,
            slugOptUpdate = slugOptUpdate,
            taglineOptUpdate = taglineOptUpdate,
            emailsOptUpdate = emailsOptUpdate,
            phoneNumbersOptUpdate = phoneNumbersOptUpdate,
            addressesOptUpdate = addressesOptUpdate,
            companyRegistrationNumberOptUpdate = companyRegistrationNumberOptUpdate,
            taxIDOptUpdate = taxIDOptUpdate,
            logoImageAssetOptUpdate = logoImageAssetOptUpdate,
          )
          .zioValue

        organizationDetailsRowUpdated shouldBe organizationDetailsRow.copy(
          name = nameOptUpdate.getOrElse(organizationDetailsRow.name),
          slug = slugOptUpdate.getOrElse(organizationDetailsRow.slug),
          tagline = taglineOptUpdate.orElse(organizationDetailsRow.tagline),
          emails = emailsOptUpdate.getOrElse(organizationDetailsRow.emails),
          phoneNumbers = phoneNumbersOptUpdate.getOrElse(organizationDetailsRow.phoneNumbers),
          organizationStage = organizationStageOptUpdate.getOrElse(organizationDetailsRow.organizationStage),
          addresses = addressesOptUpdate.getOrElse(organizationDetailsRow.addresses),
          companyRegistrationNumber =
            companyRegistrationNumberOptUpdate.orElse(organizationDetailsRow.companyRegistrationNumber),
          taxID = taxIDOptUpdate.orElse(organizationDetailsRow.taxID),
          logoImageAsset = logoImageAssetOptUpdate.orElse(organizationDetailsRow.logoImageAsset),
          updatedAt = UpdatedAt(instantNow),
        )
      }

      "fail to update the organization details with a duplicate slug" in new TestContext {
        val organizationSlug1       = OrganizationSlug.assume("slug-1")
        val organizationSlug2       = OrganizationSlug.assume("slug-2")
        val organizationDetailsRow1 = arbitrarySample[OrganizationDetailsRow]
          .copy(
            slug = organizationSlug1
          )
        val organizationDetailsRow2 = arbitrarySample[OrganizationDetailsRow]
          .copy(
            slug = organizationSlug2
          )

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(organizationDetailsRow1))
          .zioValue

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(organizationDetailsRow2))
          .zioValue

        val slugUpdate = Some(organizationDetailsRow2.slug)

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val serviceError = organizationManagementRepository
          .updateOrganization(
            organizationID = organizationDetailsRow1.organizationID,
            organizationStageOptUpdate = Some(organizationDetailsRow1.organizationStage),
            slugOptUpdate = slugUpdate,
          )
          .zioError

        serviceError.message shouldBe s"Failed to update organization with ID: [${organizationDetailsRow1.organizationID}]"
        serviceError.underlying.value shouldBe a[DbException]

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll should have size 2
        organizationDetailsRowsAll should contain theSameElementsAs List(
          organizationDetailsRow1,
          organizationDetailsRow2,
        )
      }

      "fail to update for non existing organization" in new TestContext {
        val organizationIDNonExisting = arbitrarySample[OrganizationID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val serviceError = organizationManagementRepository
          .updateOrganization(
            organizationID = organizationIDNonExisting
          )
          .zioError

        serviceError.message shouldBe s"Failed to update organization with ID: [$organizationIDNonExisting]"
        serviceError.underlying.value shouldBe a[DbException]

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue

        organizationDetailsRowsAll shouldBe empty
      }
    }

    "isOrganizationSlugExists" should {
      "return true if the organization slug exists" in new TestContext {
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]

        postgresClient
          .executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))
          .zioValue

        val slugExists = organizationManagementRepository
          .isOrganizationSlugExists(organizationDetailsRow.slug)
          .zioValue

        slugExists shouldBe true
      }

      "return false if the organization slug does not exist" in new TestContext {
        val organizationSlug = arbitrarySample[OrganizationSlug]

        val slugExists = organizationManagementRepository
          .isOrganizationSlugExists(organizationSlug)
          .zioValue

        slugExists shouldBe false
      }
    }

    "getUserOrganizations" should {
      "successfully get the user's organizations with the most recent membership first, excluding other users' memberships" in new TestContext {
        val userID      = arbitrarySample[UserID]
        val userIDOther = arbitrarySample[UserID]

        userID shouldNot equal(userIDOther)

        val organizationLogoImageAsset2 = arbitrarySample[OrganizationLogoImageAsset]
        val organizationLogoImageAsset3 = arbitrarySample[OrganizationLogoImageAsset]

        val organizationDetailsRow1 = arbitrarySample[OrganizationDetailsRow]
          .copy(logoImageAsset = None, createdAt = CreatedAt(instantNow), updatedAt = UpdatedAt(instantNow))
        val organizationDetailsRow2 = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoImageAsset = Some(organizationLogoImageAsset2),
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )
        val organizationDetailsRow3 = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoImageAsset = Some(organizationLogoImageAsset3),
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )

        List(
          organizationDetailsRow1.organizationID,
          organizationDetailsRow2.organizationID,
          organizationDetailsRow3.organizationID,
        ).distinct should have size 3
        List(
          organizationDetailsRow1.slug,
          organizationDetailsRow2.slug,
          organizationDetailsRow3.slug,
        ).distinct should have size 3

        val organizationUserRow1 = arbitrarySample[OrganizationUserRow]
          .copy(
            organizationID = organizationDetailsRow1.organizationID,
            userID = userID,
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )
        val organizationUserRow2 = arbitrarySample[OrganizationUserRow]
          .copy(
            organizationID = organizationDetailsRow2.organizationID,
            userID = userID,
            createdAt = CreatedAt(instantNow.plusSeconds(10)),
            updatedAt = UpdatedAt(instantNow.plusSeconds(10)),
          )
        val organizationUserRow3 = arbitrarySample[OrganizationUserRow]
          .copy(
            organizationID = organizationDetailsRow3.organizationID,
            userID = userID,
            createdAt = CreatedAt(instantNow.plusSeconds(20)),
            updatedAt = UpdatedAt(instantNow.plusSeconds(20)),
          )
        val organizationUserRowOther = arbitrarySample[OrganizationUserRow]
          .copy(
            organizationID = organizationDetailsRow2.organizationID,
            userID = userIDOther,
            createdAt = CreatedAt(instantNow.plusSeconds(30)),
            updatedAt = UpdatedAt(instantNow.plusSeconds(30)),
          )

        postgresClient
          .executeQuery(
            for {
              _ <- organizationDetailsQueries.insert(organizationDetailsRow1)
              _ <- organizationDetailsQueries.insert(organizationDetailsRow2)
              _ <- organizationDetailsQueries.insert(organizationDetailsRow3)
              _ <- organizationUserQueries.insert(organizationUserRow1)
              _ <- organizationUserQueries.insert(organizationUserRow2)
              _ <- organizationUserQueries.insert(organizationUserRow3)
              _ <- organizationUserQueries.insert(organizationUserRowOther)
            } yield ()
          )
          .zioValue

        val organizationAndUserDetailsRows = organizationManagementRepository
          .getUserOrganizations(userID)
          .zioValue

        organizationAndUserDetailsRows shouldBe List(
          OrganizationAndUserDetailsRow(
            organizationID = organizationDetailsRow3.organizationID,
            name = organizationDetailsRow3.name,
            slug = organizationDetailsRow3.slug,
            userRole = organizationUserRow3.userRole,
            logoImageNormalizedS3BucketKey = Some(organizationLogoImageAsset3.value.imageNormalizedS3BucketKey),
          ),
          OrganizationAndUserDetailsRow(
            organizationID = organizationDetailsRow2.organizationID,
            name = organizationDetailsRow2.name,
            slug = organizationDetailsRow2.slug,
            userRole = organizationUserRow2.userRole,
            logoImageNormalizedS3BucketKey = Some(organizationLogoImageAsset2.value.imageNormalizedS3BucketKey),
          ),
          OrganizationAndUserDetailsRow(
            organizationID = organizationDetailsRow1.organizationID,
            name = organizationDetailsRow1.name,
            slug = organizationDetailsRow1.slug,
            userRole = organizationUserRow1.userRole,
            logoImageNormalizedS3BucketKey = None,
          ),
        )
      }

      "successfully order memberships sharing a created at by organization ID ascending" in new TestContext {
        val userID = arbitrarySample[UserID]

        val organizationDetailsRow1 = arbitrarySample[OrganizationDetailsRow]
          .copy(logoImageAsset = None, createdAt = CreatedAt(instantNow), updatedAt = UpdatedAt(instantNow))
        val organizationDetailsRow2 = arbitrarySample[OrganizationDetailsRow]
          .copy(logoImageAsset = None, createdAt = CreatedAt(instantNow), updatedAt = UpdatedAt(instantNow))

        organizationDetailsRow1.organizationID shouldNot equal(organizationDetailsRow2.organizationID)
        organizationDetailsRow1.slug shouldNot equal(organizationDetailsRow2.slug)

        val organizationUserRow1 = arbitrarySample[OrganizationUserRow]
          .copy(
            organizationID = organizationDetailsRow1.organizationID,
            userID = userID,
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )
        val organizationUserRow2 = arbitrarySample[OrganizationUserRow]
          .copy(
            organizationID = organizationDetailsRow2.organizationID,
            userID = userID,
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )

        // Postgres compares uuid bytewise, which matches the lowercase hex string order but not java.util.UUID.compareTo.
        val organizationUserRowsExpectedOrder =
          List(organizationUserRow1, organizationUserRow2).sortBy(_.organizationID.value.toString)

        val organizationAndUserDetailsRowsExpected = List(
          OrganizationAndUserDetailsRow(
            organizationID = organizationDetailsRow1.organizationID,
            name = organizationDetailsRow1.name,
            slug = organizationDetailsRow1.slug,
            userRole = organizationUserRow1.userRole,
            logoImageNormalizedS3BucketKey = None,
          ),
          OrganizationAndUserDetailsRow(
            organizationID = organizationDetailsRow2.organizationID,
            name = organizationDetailsRow2.name,
            slug = organizationDetailsRow2.slug,
            userRole = organizationUserRow2.userRole,
            logoImageNormalizedS3BucketKey = None,
          ),
        ).sortBy(_.organizationID.value.toString)

        // Inserted in reverse of the expected order so insertion order cannot satisfy the assertion.
        postgresClient
          .executeQuery(
            for {
              _ <- organizationDetailsQueries.insert(organizationDetailsRow1)
              _ <- organizationDetailsQueries.insert(organizationDetailsRow2)
              _ <- ZIO.foreachDiscard(organizationUserRowsExpectedOrder.reverse)(organizationUserQueries.insert)
            } yield ()
          )
          .zioValue

        val organizationAndUserDetailsRows = organizationManagementRepository
          .getUserOrganizations(userID)
          .zioValue

        organizationAndUserDetailsRows shouldBe organizationAndUserDetailsRowsExpected
      }

      "successfully return an empty list when the user has no memberships" in new TestContext {
        val userID = arbitrarySample[UserID]

        val organizationAndUserDetailsRows = organizationManagementRepository
          .getUserOrganizations(userID)
          .zioValue

        organizationAndUserDetailsRows shouldBe Nil
      }

      "fail with an UnexpectedError when a membership's organization details row is missing" in new TestContext {
        val organizationUserRow = arbitrarySample[OrganizationUserRow]
          .copy(createdAt = CreatedAt(instantNow), updatedAt = UpdatedAt(instantNow))

        postgresClient
          .executeQuery(organizationUserQueries.insert(organizationUserRow))
          .zioValue

        val serviceError = organizationManagementRepository
          .getUserOrganizations(organizationUserRow.userID)
          .zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"Organization details not found for organizationID: [${organizationUserRow.organizationID}]"
        )
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
