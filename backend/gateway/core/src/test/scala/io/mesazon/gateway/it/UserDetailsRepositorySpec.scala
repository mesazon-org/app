package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.clock.TimeProvider
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.repository.domain.UserDetailsRow
import io.mesazon.gateway.repository.queries.UserDetailsQueries
import io.mesazon.gateway.utils.*
import io.mesazon.generator.IDGenerator
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class UserDetailsRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  override def beforeAll(): Unit = {
    super.beforeAll()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userDetailsTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = {
    super.beforeEach()

    val context = new TestContext {}
    import context.*

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userDetailsTable).zioValue
    }
  }

  "UserDetailsRepository" when {
    "insertUserDetails" should {
      "successfully insert new details for a user" in new TestContext {
        val userID = arbitrarySample[UserID]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(userID.value)
            .once(),
        )

        val email        = arbitrarySample[Email]
        val onboardStage = arbitrarySample[OnboardStage]

        val userDetailsRowInsert = userDetailsRepository.insertUserDetails(email, onboardStage).zioValue

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head shouldBe userDetailsRowInsert
        userDetailsRowsAll.head shouldBe
          UserDetailsRow(
            userID = userID,
            email = email,
            fullName = None,
            phoneNumber = None,
            onboardStage = onboardStage,
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )
      }

      "fail to insert details for already existing email" in new TestContext {
        val userDetailsRow = arbitrarySample[UserDetailsRow]

        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once(),
          (() => idGeneratorMock.generateID)
            .expects()
            .returningZIO(userDetailsRow.userID.value)
            .once(),
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val serviceError =
          userDetailsRepository.insertUserDetails(userDetailsRow.email, userDetailsRow.onboardStage).zioError

        serviceError.message shouldBe s"Failed to insertUserDetails: [${userDetailsRow.email}], [${userDetailsRow.onboardStage}]"
        serviceError.underlying.value shouldBe a[DbException]
      }
    }

    "updateUserDetails" should {
      "successfully update existing details for a user" in new TestContext {
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )

        val instantNowUpdated = instantNow.plusSeconds(10)
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNowUpdated)
            .once()
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardStageUpdate   = arbitrarySample[OnboardStage]
        val fullNameOptUpdate    = arbitrarySample[Option[FullName]]
        val phoneNumberOptUpdate = arbitrarySample[Option[PhoneNumber]]

        val userDetailsRowUpdate = userDetailsRepository
          .updateUserDetails(
            userID = userDetailsRow.userID,
            onboardStageUpdate = onboardStageUpdate,
            fullNameOptUpdate = fullNameOptUpdate,
            phoneNumberOptUpdate = phoneNumberOptUpdate,
          )
          .zioValue

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll.head.userID shouldBe userDetailsRow.userID
        userDetailsRowsAll.head.email shouldBe userDetailsRow.email
        userDetailsRowsAll.head.createdAt shouldBe userDetailsRow.createdAt
        userDetailsRowsAll.head.updatedAt should not be userDetailsRow.updatedAt
        userDetailsRowsAll.head shouldBe userDetailsRowUpdate
        userDetailsRowsAll.head shouldBe userDetailsRow.copy(
          onboardStage = onboardStageUpdate,
          fullName = fullNameOptUpdate orElse userDetailsRow.fullName,
          phoneNumber = phoneNumberOptUpdate orElse userDetailsRow.phoneNumber,
          updatedAt = UpdatedAt(instantNowUpdated),
        )
      }

      "fail to update details for a not existing user" in new TestContext {
        inSequence(
          (() => timeProviderMock.instantNow)
            .expects()
            .returningZIO(instantNow)
            .once()
        )

        val userID               = arbitrarySample[UserID]
        val onboardStageUpdate   = arbitrarySample[OnboardStage]
        val fullNameOptUpdate    = arbitrarySample[Option[FullName]]
        val phoneNumberOptUpdate = arbitrarySample[Option[PhoneNumber]]

        val serviceError = userDetailsRepository
          .updateUserDetails(
            userID = userID,
            onboardStageUpdate = onboardStageUpdate,
            fullNameOptUpdate = fullNameOptUpdate,
            phoneNumberOptUpdate = phoneNumberOptUpdate,
          )
          .zioError

        serviceError.underlying.value shouldBe a[DbException]
        serviceError.message shouldBe s"Failed to updateUserDetails: [$userID], [$onboardStageUpdate], [$fullNameOptUpdate], [$phoneNumberOptUpdate]"
      }
    }

    "getUserDetails" should {
      "successfully get existing details for a user by user ID" in new TestContext {
        val userDetailsRow = arbitrarySample[UserDetailsRow]

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userDetailsRowOptGet = userDetailsRepository
          .getUserDetails(userID = userDetailsRow.userID)
          .zioValue

        userDetailsRowOptGet shouldBe Some(userDetailsRow)
      }

      "return None when there are no user details for the given user ID" in new TestContext {
        val userID = arbitrarySample[UserID]

        val userDetailsRowOptGet = userDetailsRepository
          .getUserDetails(userID)
          .zioValue

        userDetailsRowOptGet shouldBe None
      }
    }

    "getUserDetailsByEmail" should {
      "successfully get existing user details for a user by email" in new TestContext {
        val userDetailsRow = arbitrarySample[UserDetailsRow]

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userDetailsRowOptGet = userDetailsRepository
          .getUserDetailsByEmail(email = userDetailsRow.email)
          .zioValue

        userDetailsRowOptGet shouldBe Some(userDetailsRow)
      }

      "return None when there are no details for the given email" in new TestContext {
        val email = arbitrarySample[Email]

        val userDetailsRowOptGet = userDetailsRepository
          .getUserDetailsByEmail(email)
          .zioValue

        userDetailsRowOptGet shouldBe None
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val repositoryConfig = RepositoryConfig(
      schema = "local_schema",
      userDetailsTable = "user_details",
    )

    val postgreSQLTestClientConfig = withContainers(PostgreSQLTestClientConfig.from(_))

    val postgresClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLTestClientConfig))
      .zioValue

    val userDetailsQueries =
      ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    val timeProviderMock = mock[TimeProvider]
    val idGeneratorMock  = mock[IDGenerator]

    val userDetailsRepository = ZIO
      .service[UserDetailsRepository]
      .provide(
        UserDetailsRepository.live,
        postgresClient.databaseLive,
        ZLayer.succeed(timeProviderMock),
        ZLayer.succeed(idGeneratorMock),
        ZLayer.succeed(userDetailsQueries),
      )
      .zioValue
  }
}
