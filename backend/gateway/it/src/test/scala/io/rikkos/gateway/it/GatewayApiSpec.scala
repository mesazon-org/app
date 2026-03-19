package io.rikkos.gateway.it

import fs2.io.net.Network
import io.rikkos.domain.gateway.*
import io.rikkos.gateway.it.GatewayApiSpec.Context
import io.rikkos.gateway.it.client.GatewayApiClient
import io.rikkos.gateway.it.client.GatewayApiClient.GatewayApiClientConfig
import io.rikkos.gateway.it.codec.given
import io.rikkos.gateway.repository.domain.{UserContactRow, UserDetailsRow}
import io.rikkos.gateway.repository.queries.{UserContactsQueries, UserDetailsQueries}
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.*
import io.rikkos.test.postgresql.PostgreSQLTestClient
import io.rikkos.test.postgresql.PostgreSQLTestClient.PostgreSQLTestClientConfig
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.http4s.Status
import zio.*
import zio.interop.catz.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class GatewayApiSpec
    extends ZWordSpecBase,
      DockerComposeBase,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  given Network[Task] = Network.forAsync[Task]

  override def exposedServices = GatewayApiClient.ExposedServices ++ PostgreSQLTestClient.ExposedServices

  def withContext[A](f: Context => A): A = withContainers { container =>
    val context = for {
      postgreSQLClientConfig = PostgreSQLTestClientConfig.from(container)
      gatewayApiClientConfig = GatewayApiClientConfig.from(container)
      postgreSQLClient <- ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(postgreSQLClientConfig))
      gatewayApiClient <- ZIO
        .service[GatewayApiClient]
        .provide(GatewayApiClient.live, ZLayer.succeed(gatewayApiClientConfig))
    } yield Context(gatewayApiClient, postgreSQLClient)

    f(context.zioValue)
  }

  override def beforeAll(): Unit = withContext { case Context(gatewayClient, _) =>
    super.beforeAll()

    // Ensure the GatewayApiClient is initialized before running tests
    eventually(
      gatewayClient.readiness.zioValue shouldBe Status.NoContent
    )
  }

  override def beforeEach(): Unit = withContext { case Context(_, postgresSQLClient) =>
    super.beforeEach()

    // Truncate the table before each test to ensure a clean state
    eventually {
      postgresSQLClient.truncateTable("local_schema", "users_details").zioValue
      postgresSQLClient.truncateTable("local_schema", "users_contacts").zioValue
    }
  }

  "GatewayApi" when {
    "/users/onboard" should {
      "return successfully when onboarding user" in withContext { case Context(gatewayClient, postgresSQLClient) =>
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]

        gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent

        val userDetailsRowResponse = postgresSQLClient.database
          .transactionOrDie(
            UserDetailsQueries.getUserDetailsQuery(UserID.assume("test"))
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

      "fail with BadRequest when onboarding user details are invalid" in withContext { case Context(gatewayClient, _) =>
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
          .copy(firstName = "")

        gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }

      "fail with Conflict when onboarding user details insert user twice" in withContext {
        case Context(gatewayClient, _) =>
          val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]

          gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe Status.NoContent

          gatewayClient.onboardUser(onboardUserDetailsRequest).zioValue shouldBe Status.Conflict
      }
    }

    "/users/update" should {
      "return successfully when update user" in withContext { case Context(gatewayClient, postgresSQLClient) =>
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = UserID.assume("test"), email = Email.assume("eliot.martel@gmail.com"))
        val phoneNationalNumber      = "99123123"
        val phoneRegion              = "CY"
        val phoneNumber              = PhoneNumberE164.cy(phoneNationalNumber)
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
          .copy(phoneNationalNumber = Some(phoneNationalNumber))
          .copy(phoneRegion = Some(phoneRegion))

        postgresSQLClient.database
          .transactionOrDie(UserDetailsQueries.insertUserDetailsQuery(userDetailsRow))
          .zioValue

        gatewayClient.updateUser(updateUserDetailsRequest).zioValue shouldBe Status.NoContent

        val updatedUserDetailsRow = postgresSQLClient.database
          .transactionOrDie(
            UserDetailsQueries.getUserDetailsQuery(UserID.assume("test"))
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

      "fail with BadRequest when update user details are invalid" in withContext { case Context(gatewayClient, _) =>
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
          .copy(firstName = Some(""))

        gatewayClient.updateUser(updateUserDetailsRequest).zioValue shouldBe Status.BadRequest
      }
    }

    "/contacts/upsert" should {
      "return successfully when upserting user contacts" in withContext {
        case Context(gatewayClient, postgresSQLClient) =>
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

          postgresSQLClient.database
            .transactionOrDie(UserDetailsQueries.insertUserDetailsQuery(userDetailsRow))
            .zioValue

          postgresSQLClient.database
            .transactionOrDie(UserContactsQueries.insertUserContacts(NonEmptyChunk(insertUserContactsRow)))
            .zioValue

          gatewayClient
            .upsertUserContacts(NonEmptyChunk(updateUserContact, insertUserContact))
            .zioValue shouldBe Status.NoContent

          val userContactsRow = postgresSQLClient.database
            .transactionOrDie(UserContactsQueries.getUserContacts(userID))
            .zioValue

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

      "fail with BadRequest user contacts are invalid" in withContext { case Context(gatewayClient, _) =>
        val invalidUpsertUserContactRequest = arbitrarySample[smithy.UpsertUserContactRequest]
          .copy(firstName = "")

        gatewayClient
          .upsertUserContacts(NonEmptyChunk(invalidUpsertUserContactRequest))
          .zioValue shouldBe Status.BadRequest
      }
    }
  }
}

object GatewayApiSpec {

  case class Context(gatewayClient: GatewayApiClient, postgresSQLClient: PostgreSQLTestClient)
}
