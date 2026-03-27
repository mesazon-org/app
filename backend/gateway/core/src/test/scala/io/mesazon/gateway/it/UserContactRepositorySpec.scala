package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import io.github.gaelrenoux.tranzactio.DbException
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.Mocks
import io.mesazon.gateway.config.RepositoryConfig
import io.mesazon.gateway.repository.UserContactRepository
import io.mesazon.gateway.repository.domain.{UserContactRow, UserDetailsRow}
import io.mesazon.gateway.repository.queries.{UserContactQueries, UserManagementQueries}
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.test.postgresql.PostgreSQLTestClient
import io.mesazon.testkit.base.{DockerComposeBase, GatewayArbitraries, ZWordSpecBase}
import io.scalaland.chimney.dsl.*
import zio.*

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant, ZoneOffset}

import PostgreSQLTestClient.PostgreSQLTestClientConfig

class UserContactRepositorySpec extends ZWordSpecBase, GatewayArbitraries, RepositoryArbitraries, DockerComposeBase {
  override def dockerComposeFile: String = "./src/test/resources/compose.yaml"

  override def exposedServices: Set[ExposedService] = PostgreSQLTestClient.ExposedServices

  val repositoryConfig = RepositoryConfig(
    schema = "local_schema",
    userOnboardTable = "user_onboard",
    userDetailsTable = "user_details",
    userContactTable = "user_contact",
  )

  val repositoryConfigLive = ZLayer.succeed(repositoryConfig)

  def withContext[A](f: (PostgreSQLTestClient, UserContactQueries, UserManagementQueries) => A): A = withContainers {
    container =>
      val config = PostgreSQLTestClientConfig.from(container)

      val postgreSQLTestClient = ZIO
        .service[PostgreSQLTestClient]
        .provide(PostgreSQLTestClient.live, ZLayer.succeed(config))
        .zioValue

      val userContactQueries =
        ZIO
          .service[UserContactQueries]
          .provide(UserContactQueries.live, repositoryConfigLive)
          .zioValue

      val userManagementQueries =
        ZIO
          .service[UserManagementQueries]
          .provide(UserManagementQueries.live, repositoryConfigLive)
          .zioValue

      f(postgreSQLTestClient, userContactQueries, userManagementQueries)
  }

  override def beforeAll(): Unit = withContext { (client, _, _) =>
    super.beforeAll()
    eventually {
      client.checkIfTableExists(repositoryConfig.schema, repositoryConfig.userContactTable).zioValue shouldBe true
      client.checkIfTableExists(repositoryConfig.schema, repositoryConfig.userDetailsTable).zioValue shouldBe true
    }
  }

  override def beforeEach(): Unit = withContext { (client, _, _) =>
    super.beforeEach()
    eventually {
      client.truncateTable(repositoryConfig.schema, repositoryConfig.userContactTable).zioValue
      client.truncateTable(repositoryConfig.schema, repositoryConfig.userDetailsTable).zioValue
    }
  }

  "UserContactRepository" when {
    "upsertUserContacts" should {
      "successfully upsert user contacts" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
          val userID         = arbitrarySample[UserID]
          val userContactID1 = arbitrarySample[UserContactID]
          val userContactID2 = arbitrarySample[UserContactID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val upsertUserContact1 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID1))
          val upsertUserContact1_1 = upsertUserContact1
            .copy(phoneNumber = PhoneNumberE164.assume("11892"))
          val upsertUserContact2 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID2))
          val upsertUserContact2_1 = upsertUserContact2
            .copy(displayName = DisplayName.assume("displayName"))
          val upsertUserContact3 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None)
          val upsertUserContact4 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None)

          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          val insertUserContactRows = NonEmptyChunk(upsertUserContact1, upsertUserContact2).map(
            _.into[UserContactRow]
              .withFieldComputed(_.userContactID, _.userContactID.value)
              .withFieldConst(_.userID, userID)
              .withFieldConst(_.createdAt, CreatedAt(now))
              .withFieldConst(_.updatedAt, UpdatedAt(now))
              .transform
          )

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          postgresClient.executeQuery(userContactQueries.insertUserContacts(insertUserContactRows)).zioValue

          userContactsRepository
            .upsertUserContacts(
              userID,
              NonEmptyChunk(
                upsertUserContact1_1,
                upsertUserContact2_1,
                upsertUserContact3,
                upsertUserContact4,
              ),
            )
            .zioValue

          val upsertUserContact3_1 = upsertUserContact3
            .copy(userContactID = Some(UserContactID.assume("1")))
          val upsertUserContact4_1 = upsertUserContact4
            .copy(userContactID = Some(UserContactID.assume("2")))

          postgresClient
            .executeQuery(
              userContactQueries.getUserContacts(userID)
            )
            .zioValue should contain theSameElementsAs Vector(
            upsertUserContact1_1,
            upsertUserContact2_1,
            upsertUserContact3_1,
            upsertUserContact4_1,
          ).map {
            _.into[UserContactRow]
              .withFieldComputed(_.userContactID, _.userContactID.value)
              .withFieldConst(_.userID, userID)
              .withFieldConst(_.createdAt, CreatedAt(now))
              .withFieldConst(_.updatedAt, UpdatedAt(now))
              .transform
          }
      }

      "successfully update only relevant fields when receive an update" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val userID         = arbitrarySample[UserID]
          val userContactID  = arbitrarySample[UserContactID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val upsertUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID))
          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.systemUTC()),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          val insertUserContact = upsertUserContact
            .into[UserContactRow]
            .withFieldComputed(_.userContactID, _.userContactID.value)
            .withFieldConst(_.userID, userID)
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          postgresClient.executeQuery(userContactQueries.insertUserContacts(NonEmptyChunk(insertUserContact))).zioValue

          val insertedUserContactRow =
            postgresClient.executeQuery(userContactQueries.getUserContacts(userID)).zioValue.head

          val updatedUpsertUserContact = upsertUserContact.copy(
            displayName = DisplayName.assume("displayName"),
            firstName = FirstName.assume("firstName"),
            phoneNumber = PhoneNumberE164.assume("1234567890"),
            lastName = Some(LastName.assume("lastName")),
            company = Some(Company.assume("company")),
            email = Some(Email.assume("email")),
            addressLine1 = Some(AddressLine1.assume("addressLine1")),
            addressLine2 = Some(AddressLine2.assume("addressLine2")),
            city = Some(City.assume("city")),
            postalCode = Some(PostalCode.assume("postalCode")),
          )

          // Upsert user contact with updated fields
          userContactsRepository.upsertUserContacts(userID, NonEmptyChunk(updatedUpsertUserContact)).zioValue

          val updatedUserContactRow =
            postgresClient.executeQuery(userContactQueries.getUserContacts(userID)).zioValue.head

          updatedUserContactRow shouldBe insertedUserContactRow.copy(
            displayName = updatedUserContactRow.displayName,
            firstName = updatedUserContactRow.firstName,
            phoneNumber = updatedUserContactRow.phoneNumber,
            lastName = updatedUserContactRow.lastName,
            company = updatedUserContactRow.company,
            email = updatedUserContactRow.email,
            addressLine1 = updatedUserContactRow.addressLine1,
            addressLine2 = updatedUserContactRow.addressLine2,
            city = updatedUserContactRow.city,
            postalCode = updatedUserContactRow.postalCode,
            updatedAt = updatedUserContactRow.updatedAt,
          )
      }

      "successfully update user contacts when update for user contact phone number occurs along with insert of new user contact with already inserted phone number" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val clockNow       = Clock.fixed(now, ZoneOffset.UTC)
          val userID         = arbitrarySample[UserID]
          val userContactID  = arbitrarySample[UserContactID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val upsertUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID))
          val updatePhoneNumberUserContact = upsertUserContact
            .copy(phoneNumber = PhoneNumberE164.assume("123"))
          val insertSamePhoneNumberUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None, phoneNumber = upsertUserContact.phoneNumber)

          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(clockNow),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          val insertUserContactsRow = upsertUserContact
            .into[UserContactRow]
            .withFieldComputed(_.userContactID, _.userContactID.value)
            .withFieldConst(_.userID, userID)
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          postgresClient
            .executeQuery(
              userContactQueries.insertUserContacts(NonEmptyChunk(insertUserContactsRow))
            )
            .zioValue

          userContactsRepository
            .upsertUserContacts(userID, NonEmptyChunk(updatePhoneNumberUserContact, insertSamePhoneNumberUserContact))
            .zioValue

          val insertedNonConflictedUserContact = insertSamePhoneNumberUserContact
            .copy(userContactID = Some(UserContactID.assume("1")))

          postgresClient
            .executeQuery(
              userContactQueries.getUserContacts(userID)
            )
            .zioValue should contain theSameElementsAs Vector(
            updatePhoneNumberUserContact,
            insertedNonConflictedUserContact,
          ).map {
            _.into[UserContactRow]
              .withFieldComputed(_.userContactID, _.userContactID.value)
              .withFieldConst(_.userID, userID)
              .withFieldConst(_.createdAt, CreatedAt(now))
              .withFieldConst(_.updatedAt, UpdatedAt(now))
              .transform
          }
      }

      "successfully update occurs on user contact that is not found, entry should remain empty" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val userID         = arbitrarySample[UserID]
          val userContactID  = arbitrarySample[UserContactID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val upsertUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID))
          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.systemUTC()),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          userContactsRepository
            .upsertUserContacts(userID, NonEmptyChunk(upsertUserContact))
            .zioValue

          postgresClient.executeQuery(userContactQueries.getUserContacts(userID)).zioValue shouldBe empty
      }

      "fail to insert new user contacts when a user contact with the same phone number and user id already exist" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val userID         = arbitrarySample[UserID]
          val userContactID  = arbitrarySample[UserContactID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val upsertUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID))
          val conflictUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None, phoneNumber = upsertUserContact.phoneNumber)

          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.systemUTC()),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          val insertUserContactsRow = upsertUserContact
            .into[UserContactRow]
            .withFieldComputed(_.userContactID, _.userContactID.value)
            .withFieldConst(_.userID, userID)
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          postgresClient
            .executeQuery(
              userContactQueries.insertUserContacts(NonEmptyChunk(insertUserContactsRow))
            )
            .zioValue

          userContactsRepository
            .upsertUserContacts(userID, NonEmptyChunk(conflictUserContact))
            .zioCause
            .dieOption
            .get
            .asInstanceOf[DbException.Wrapped]
            .cause shouldBe a[java.sql.BatchUpdateException]
      }

      "fail to insert multiple user contact with the same phone number and user in one query" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val userID         = arbitrarySample[UserID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val conflictUserContact1 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None)
          val conflictUserContact2 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None, phoneNumber = conflictUserContact1.phoneNumber)

          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.systemUTC()),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          userContactsRepository
            .upsertUserContacts(userID, NonEmptyChunk(conflictUserContact1, conflictUserContact2))
            .zioCause
            .dieOption
            .get
            .asInstanceOf[DbException.Wrapped]
            .cause shouldBe a[java.sql.BatchUpdateException]
      }

      "fail to update user contact if conflicted user contact is received" in withContext {
        (postgresClient, userContactQueries, userManagementQueries) =>
          val now            = Instant.now().truncatedTo(ChronoUnit.MILLIS)
          val userID         = arbitrarySample[UserID]
          val userContactID  = arbitrarySample[UserContactID]
          val userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(userID = userID)
          val upsertUserContact1 = arbitrarySample[UpsertUserContact]
            .copy(userContactID = Some(userContactID))
          val upsertUserContact1_1 = upsertUserContact1
            .copy(displayName = DisplayName.assume("123"))
          val conflictUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None, phoneNumber = upsertUserContact1.phoneNumber)

          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.systemUTC()),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          val insertUserContactsRow = upsertUserContact1
            .into[UserContactRow]
            .withFieldComputed(_.userContactID, _.userContactID.value)
            .withFieldConst(_.userID, userID)
            .withFieldConst(_.createdAt, CreatedAt(now))
            .withFieldConst(_.updatedAt, UpdatedAt(now))
            .transform

          // Insert user for user_id foreign key constraint
          postgresClient.executeQuery(userManagementQueries.insertUserDetailsQuery(userDetailsRow)).zioValue

          postgresClient
            .executeQuery(
              userContactQueries.insertUserContacts(NonEmptyChunk(insertUserContactsRow))
            )
            .zioValue

          userContactsRepository
            .upsertUserContacts(userID, NonEmptyChunk(conflictUserContact, upsertUserContact1_1))
            .zioCause
            .dieOption
            .get
            .asInstanceOf[DbException.Wrapped]
            .cause shouldBe a[java.sql.BatchUpdateException]

          postgresClient
            .executeQuery(
              userContactQueries.getUserContacts(userID)
            )
            .zioValue should contain theSameElementsAs Vector(insertUserContactsRow)
      }

      "fail to insert user contact for a user with user id that doesn't existing" in withContext {
        (postgresClient, userContactQueries, _) =>
          val userID            = arbitrarySample[UserID]
          val upsertUserContact = arbitrarySample[UpsertUserContact]
            .copy(userContactID = None)

          val userContactsRepository = ZIO
            .service[UserContactRepository]
            .provide(
              UserContactRepository.live,
              ZLayer.succeed(postgresClient.database),
              Mocks.timeProviderLive(Clock.systemUTC()),
              Mocks.idGeneratorLive,
              ZLayer.succeed(userContactQueries),
            )
            .zioValue

          userContactsRepository
            .upsertUserContacts(userID, NonEmptyChunk(upsertUserContact))
            .zioCause
            .dieOption
            .get
            .asInstanceOf[DbException.Wrapped]
            .cause shouldBe a[java.sql.BatchUpdateException]
      }
    }
  }
}
