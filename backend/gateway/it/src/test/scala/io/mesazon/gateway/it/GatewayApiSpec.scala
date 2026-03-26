package io.mesazon.gateway.it

import fs2.io.net.Network
import io.mesazon.domain.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.GatewayClientConfig
import io.mesazon.gateway.repository.domain.{UserContactRow, UserDetailsRow}
import io.mesazon.gateway.repository.queries.{UserContactQueries, UserManagementQueries}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.{RepositoryArbitraries, SmithyArbitraries}
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.{DockerComposeBase, IronRefinedTypeTransformer, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import sttp.model.*
import zio.*
import zio.interop.catz.*

import java.time.Instant
import java.time.temporal.ChronoUnit

import GatewayApiSpec.Context
import PostgreSQLTestClient.PostgreSQLTestClientConfig

class GatewayApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  given Network[Task] = Network.forAsync[Task]

  override def exposedServices = GatewayClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayClientConfig.from(container)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayClient]
        .provide(GatewayClient.live, ZLayer.succeed(gatewayApiClientConfig))
      userManagementQueries <- ZIO
        .service[UserManagementQueries]
        .provide(UserManagementQueries.live, RepositoryConfig.live, appNameLive)
      userContactQueries <- ZIO
        .service[UserContactQueries]
        .provide(UserContactQueries.live, RepositoryConfig.live, appNameLive)
    } yield Context(gatewayApiClient, postgreSQLClient, userManagementQueries, userContactQueries)

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually(
      gatewayClient.readiness.zioValue shouldBe StatusCode.NoContent
    )
  }

  override def beforeEach(): Unit = withContext { context =>
    import context.*

    super.beforeEach()

    // Truncate the table before each test to ensure a clean state
    eventually {
      postgresSQLClient.truncateTable("local_schema", "user_details").zioValue
      postgresSQLClient.truncateTable("local_schema", "user_contact").zioValue
    }
  }

  "GatewayApi" when {
    "/users/onboard" should {
      "return successfully when onboarding user" in withContext { context =>
        import context.*

        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]

        gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe StatusCode.NoContent

        val userDetailsRowResponse = postgresSQLClient
          .executeQuery(
            userManagementQueries.getUserDetailsQuery(UserID.assume("test"))
          )
          .zioValue
          .value

        userDetailsRowResponse shouldBe onboardUserDetailsRequest
          .into[UserDetailsRow]
          .withFieldConst(_.phoneNumber, PhoneNumberE164.cy(onboardUserDetailsRequest.phoneNationalNumber))
          .withFieldConst(_.userID, UserID.assume("test"))
          .withFieldConst(_.email, Email.assume("eliot.martel@gmail.com"))
          .withFieldConst(_.createdAt, userDetailsRowResponse.createdAt)
          .withFieldConst(_.updatedAt, userDetailsRowResponse.updatedAt)
          .transform
      }

      "fail with BadRequest when onboarding user details are invalid" in withContext { context =>
        import context.*

        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
          .copy(firstName = "")

        gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe StatusCode.BadRequest
      }

      "fail with Conflict when onboarding user details insert user twice" in withContext { context =>
        import context.*

        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]

        gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe StatusCode.NoContent

        gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe StatusCode.Conflict
      }
    }

    "/users/update" should {
      "return successfully when update user" in withContext { context =>
        import context.*

        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = UserID.assume("test"), email = Email.assume("eliot.martel@gmail.com"))
        val phoneNationalNumber      = "99123123"
        val phoneRegion              = "CY"
        val phoneNumber              = PhoneNumberE164.cy(phoneNationalNumber)
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
          .copy(phoneNationalNumber = Some(phoneNationalNumber))
          .copy(phoneRegion = Some(phoneRegion))

        postgresSQLClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

        gatewayClient.updateUser(updateUserDetailsRequest).zioValue shouldBe StatusCode.NoContent

        val updatedUserDetailsRow = postgresSQLClient
          .executeQuery(
            userManagementQueries.getUserDetailsQuery(UserID.assume("test"))
          )
          .zioValue
          .value

        val expectedUserDetailsRow = userDetailsRow.copy(
          firstName = FirstName.assumeAll(updateUserDetailsRequest.firstName).getOrElse(userDetailsRow.firstName),
          lastName = LastName.assumeAll(updateUserDetailsRequest.lastName).getOrElse(userDetailsRow.lastName),
          phoneNumber = phoneNumber,
          addressLine1 =
            AddressLine1.assumeAll(updateUserDetailsRequest.addressLine1).getOrElse(userDetailsRow.addressLine1),
          addressLine2 =
            AddressLine2.assumeAll(updateUserDetailsRequest.addressLine2).orElse(userDetailsRow.addressLine2),
          city = City.assumeAll(updateUserDetailsRequest.city).getOrElse(userDetailsRow.city),
          postalCode = PostalCode.assumeAll(updateUserDetailsRequest.postalCode).getOrElse(userDetailsRow.postalCode),
          company = Company.assumeAll(updateUserDetailsRequest.company).getOrElse(userDetailsRow.company),
          updatedAt = updatedUserDetailsRow.updatedAt, // This should be updated to the current time
        )

        updatedUserDetailsRow shouldBe expectedUserDetailsRow
      }

      "fail with BadRequest when update user details are invalid" in withContext { context =>
        import context.*

        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
          .copy(firstName = Some(""))

        gatewayClient.updateUser(updateUserDetailsRequest).zioValue shouldBe StatusCode.BadRequest
      }
    }

    "/contacts/upsert" should {
      "return successfully when upserting user contacts" in withContext { context =>
        import context.*

        val now                 = Instant.now().truncatedTo(ChronoUnit.MILLIS)
        val userID              = UserID.assume("test")
        val userContactID       = arbitrarySample[UserContactID]
        val updateDisplayName   = DisplayName.assume("dummy")
        val userDetailsRow      = arbitrarySample[UserDetailsRow].copy(userID = userID)
        val existingUserContact = arbitrarySample[smithy.UpsertUserContactRequest]
          .copy(userContactID = Some(userContactID.value))
        val updateUserContact = existingUserContact
          .copy(displayName = updateDisplayName.value)
        val insertUserContact = arbitrarySample[smithy.UpsertUserContactRequest]
          .copy(userContactID = None)

        val insertUserContactsRow = existingUserContact
          .into[UserContactRow]
          .withFieldComputed(_.userContactID, uc => UserContactID.assume(uc.userContactID.value))
          .withFieldConst(_.phoneNumber, PhoneNumberE164.cy(existingUserContact.phoneNationalNumber))
          .withFieldConst(_.userID, userID)
          .withFieldConst(_.createdAt, CreatedAt(now))
          .withFieldConst(_.updatedAt, UpdatedAt(now))
          .transform

        postgresSQLClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

        postgresSQLClient
          .executeQuery(userContactQueries.insertUserContacts(NonEmptyChunk(insertUserContactsRow)))
          .zioValue

        gatewayClient
          .upsertUserContacts(List(updateUserContact, insertUserContact))
          .zioValue shouldBe StatusCode.NoContent

        val userContactsRow = postgresSQLClient.executeQuery(userContactQueries.getUserContacts(userID)).zioValue

        userContactsRow should have size 2

        val updatedUserContactRow = userContactsRow
          .filter(_.userContactID == insertUserContactsRow.userContactID)
          .head

        val newUserContactRow = userContactsRow
          .filter(_.userContactID != insertUserContactsRow.userContactID)
          .head

        updatedUserContactRow shouldBe insertUserContactsRow.copy(
          displayName = updateDisplayName,
          updatedAt = updatedUserContactRow.updatedAt,
        )

        newUserContactRow shouldBe insertUserContact
          .into[UserContactRow]
          .withFieldConst(_.userContactID, newUserContactRow.userContactID)
          .withFieldConst(_.userID, userID)
          .withFieldConst(_.phoneNumber, PhoneNumberE164.cy(insertUserContact.phoneNationalNumber))
          .withFieldConst(_.createdAt, newUserContactRow.createdAt)
          .withFieldConst(_.updatedAt, newUserContactRow.updatedAt)
          .transform
      }

      "fail with BadRequest user contacts are invalid" in withContext { context =>
        import context.*

        val invalidUpsertUserContactRequest = arbitrarySample[smithy.UpsertUserContactRequest]
          .copy(firstName = "")

        gatewayClient
          .upsertUserContacts(List(invalidUpsertUserContactRequest))
          .zioValue shouldBe StatusCode.BadRequest
      }
    }
  }
}

object GatewayApiSpec {

  case class Context(
      gatewayClient: GatewayClient,
      postgresSQLClient: PostgreSQLTestClient,
      userManagementQueries: UserManagementQueries,
      userContactQueries: UserContactQueries,
  )
}
