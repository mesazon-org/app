package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.UserDetailsRepository
import io.mesazon.gateway.repository.domain.UserDetailsRow
import io.mesazon.gateway.repository.queries.UserDetailsQueries
import io.mesazon.gateway.utils.*
import io.mesazon.test.postgresql.*
import io.mesazon.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.mesazon.testkit.base.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

class UserDetailsRepositorySpec extends ZWordSpecBase, RepositoryArbitraries, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/repository.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userDetailsTable = "user_details",
  )

  case class Context(
      postgresClient: PostgreSQLTestClient,
      userDetailsQueries: UserDetailsQueries,
  )

  def withContext[A](f: Context => A): A = withContainers { container =>
    val config               = PostgreSQLTestClientConfig.from(container)
    val postgreSQLTestClient = ZIO
      .service[PostgreSQLTestClient]
      .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
      .zioValue
    val userDetailsQueries =
      ZIO
        .service[UserDetailsQueries]
        .provide(UserDetailsQueries.live, ZLayer.succeed(repositoryConfig))
        .zioValue

    f(Context(postgreSQLTestClient, userDetailsQueries))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      postgresClient
        .checkIfTableExists(repositoryConfig.schema, repositoryConfig.userDetailsTable)
        .zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    eventually {
      postgresClient.truncateTable(repositoryConfig.schema, repositoryConfig.userDetailsTable).zioValue
    }
  }

  "UserDetailsRepository" when {
    "insertUserDetails" should {
      "successfully insert new details for a user" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(instantNow, ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val email        = arbitrarySample[Email]
        val onboardStage = arbitrarySample[OnboardStage]

        userDetailsRepository.insertUserDetails(email, onboardStage).zioValue

        val userDetailsRowsAll =
          postgresClient.executeQuery(userDetailsQueries.getAllUserDetailsTesting).zioValue

        userDetailsRowsAll should have size 1
        userDetailsRowsAll should contain theSameElementsAs List(
          UserDetailsRow(
            userID = UserID.assume("1"),
            email = email,
            fullName = None,
            phoneNumber = None,
            onboardStage = onboardStage,
            createdAt = CreatedAt(instantNow),
            updatedAt = UpdatedAt(instantNow),
          )
        )
      }

      "fail to insert details for already existing email" in withContext { context =>
        import context.*

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val userDetailsRow = arbitrarySample[UserDetailsRow]

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val serviceError =
          userDetailsRepository.insertUserDetails(userDetailsRow.email, userDetailsRow.onboardStage).zioError

        serviceError.message shouldBe s"Failed to insertUserDetails: [${userDetailsRow.email}], [${userDetailsRow.onboardStage}]"
        serviceError.underlying.value shouldBe a[DbException]
      }
    }

    "updateUserDetails" should {
      "successfully update existing details for a user" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(instantNow, ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val userDetailsRow = arbitrarySample[UserDetailsRow]

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val onboardStageUpdate   = arbitrarySample[OnboardStage]
        val fullNameOptUpdate    = arbitrarySample[Option[FullName]]
        val phoneNumberOptUpdate = arbitrarySample[Option[PhoneNumber]]

        userDetailsRepository
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
        userDetailsRowsAll should contain theSameElementsAs List(
          userDetailsRow.copy(
            onboardStage = onboardStageUpdate,
            fullName = fullNameOptUpdate orElse userDetailsRow.fullName,
            phoneNumber = phoneNumberOptUpdate orElse userDetailsRow.phoneNumber,
            updatedAt = UpdatedAt(instantNow),
          )
        )
      }

      "fail to update details for a not existing user" in withContext { context =>
        import context.*

        val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(instantNow, ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

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
      "successfully retrieve existing details for a user by user ID" in withContext { context =>
        import context.*

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val userDetailsRow = arbitrarySample[UserDetailsRow]

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userDetailsRowOptRetrieved = userDetailsRepository
          .getUserDetails(userID = userDetailsRow.userID)
          .zioValue

        userDetailsRowOptRetrieved shouldBe Some(userDetailsRow)
      }

      "return None when there are no user details for the given user ID" in withContext { context =>
        import context.*

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val userID = arbitrarySample[UserID]

        val userDetailsRowOptRetrieved = userDetailsRepository
          .getUserDetails(userID)
          .zioValue

        userDetailsRowOptRetrieved shouldBe None
      }
    }

    "getUserDetailsByEmail" should {
      "successfully retrieve existing user details for a user by email" in withContext { context =>
        import context.*

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val userDetailsRow = arbitrarySample[UserDetailsRow]

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val userDetailsRowOptRetrieved = userDetailsRepository
          .getUserDetailsByEmail(email = userDetailsRow.email)
          .zioValue

        userDetailsRowOptRetrieved shouldBe Some(userDetailsRow)
      }

      "return None when there are no details for the given email" in withContext { context =>
        import context.*

        val userDetailsRepository = ZIO
          .service[UserDetailsRepository]
          .provide(
            UserDetailsRepository.live,
            ZLayer.succeed(postgresClient.database),
            Mocks.timeProviderLive(Clock.fixed(Instant.now(), ZoneOffset.UTC)),
            Mocks.idGeneratorLive,
            ZLayer.succeed(userDetailsQueries),
          )
          .zioValue

        val email = arbitrarySample[Email]

        val userDetailsRowOptRetrieved = userDetailsRepository
          .getUserDetailsByEmail(email)
          .zioValue

        userDetailsRowOptRetrieved shouldBe None
      }
    }
  }
}
