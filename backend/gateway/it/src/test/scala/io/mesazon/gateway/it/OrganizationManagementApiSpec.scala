package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

@DoNotDiscover
class OrganizationManagementApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      OrganizationManagementSmithyArbitraries,
      RepositoryArbitraries {

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      for {
        _ <- postgresClient.truncateTable(
          repositoryConfig.schema,
          repositoryConfig.userDetailsTable,
          repositoryConfig.organizationDetailsTable,
          repositoryConfig.organizationUserTable,
        )
        _ <- mailHogClient.clearInbox()
      } yield ()
    }
  }

  "Organization Management Service API" when {
    "POST /create/organization" should {
      "successfully create organization" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          createOrganizationPostResponse <- gatewayClient.createOrganizationPost[smithy.InternalServerError](
            createOrganizationPostRequest.name,
            createOrganizationPostRequest.slug,
            createOrganizationPostRequest.tagline,
            createOrganizationPostRequest.emails,
            createOrganizationPostRequest.phoneNumbers,
            createOrganizationPostRequest.addresses,
            createOrganizationPostRequest.companyRegistrationNumber,
            createOrganizationPostRequest.taxID,
            Some(accessJwt.accessToken),
          )

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll should have size 1
          _ = organizationDetailsRowsAll.head shouldBe OrganizationDetailsRow(
            organizationID = organizationDetailsRowsAll.head.organizationID,
            name = createOrganizationPostRequest.name,
            slug = createOrganizationPostRequest.slug,
            tagline = createOrganizationPostRequest.tagline,
            emails = createOrganizationPostRequest.emails,
            phoneNumbers = createOrganizationPostRequest.phoneNumbers,
            organizationStage = OrganizationStage.DetailsProvided,
            addresses = createOrganizationPostRequest.addresses,
            companyRegistrationNumber = createOrganizationPostRequest.companyRegistrationNumber,
            taxID = createOrganizationPostRequest.taxID,
            logoImageAsset = None,
            createdAt = organizationDetailsRowsAll.head.createdAt,
            updatedAt = organizationDetailsRowsAll.head.updatedAt,
          )

          _ = createOrganizationPostResponse.code shouldBe StatusCode.Ok
          _ = createOrganizationPostResponse.body.value shouldBe smithy.CreateOrganizationPostResponse(organizationID =
            organizationDetailsRowsAll.head.organizationID.value
          )

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 1

          organizationUserRowsAll <- postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting)

          _ = organizationUserRowsAll should have size 1
          _ = organizationUserRowsAll.head shouldBe OrganizationUserRow(
            organizationDetailsRowsAll.head.organizationID,
            userDetailsRow.userID,
            OrganizationUserRole.Owner,
            organizationUserRowsAll.head.createdAt,
            organizationUserRowsAll.head.updatedAt,
          )
        } yield ()
      }

      "successfully create organization with only the required fields" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          createOrganizationPostRequest = CreateOrganizationPostRequest(
            name = arbitrarySample[OrganizationName],
            slug = arbitrarySample[OrganizationSlug],
            tagline = None,
            emails = List.empty,
            phoneNumbers = List.empty,
            addresses = List.empty,
            companyRegistrationNumber = None,
            taxID = None,
          )

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          createOrganizationPostResponse <- gatewayClient.createOrganizationPost[smithy.InternalServerError](
            createOrganizationPostRequest.name,
            createOrganizationPostRequest.slug,
            createOrganizationPostRequest.tagline,
            createOrganizationPostRequest.emails,
            createOrganizationPostRequest.phoneNumbers,
            createOrganizationPostRequest.addresses,
            createOrganizationPostRequest.companyRegistrationNumber,
            createOrganizationPostRequest.taxID,
            Some(accessJwt.accessToken),
          )

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll should have size 1
          _ = organizationDetailsRowsAll.head shouldBe OrganizationDetailsRow(
            organizationID = organizationDetailsRowsAll.head.organizationID,
            name = createOrganizationPostRequest.name,
            slug = createOrganizationPostRequest.slug,
            tagline = None,
            emails = List.empty,
            phoneNumbers = List.empty,
            organizationStage = OrganizationStage.DetailsProvided,
            addresses = List.empty,
            companyRegistrationNumber = None,
            taxID = None,
            logoImageAsset = None,
            createdAt = organizationDetailsRowsAll.head.createdAt,
            updatedAt = organizationDetailsRowsAll.head.updatedAt,
          )

          _ = createOrganizationPostResponse.code shouldBe StatusCode.Ok
          _ = createOrganizationPostResponse.body.value shouldBe smithy.CreateOrganizationPostResponse(organizationID =
            organizationDetailsRowsAll.head.organizationID.value
          )

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 1

          organizationUserRowsAll <- postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting)

          _ = organizationUserRowsAll should have size 1
          _ = organizationUserRowsAll.head shouldBe OrganizationUserRow(
            organizationDetailsRowsAll.head.organizationID,
            userDetailsRow.userID,
            OrganizationUserRole.Owner,
            organizationUserRowsAll.head.createdAt,
            organizationUserRowsAll.head.updatedAt,
          )
        } yield ()
      }

      "fail with BadRequest ValidationError when the name is empty" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]
            .copy(name = OrganizationName.assume(""))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          createOrganizationPostResponse <- gatewayClient.createOrganizationPost[smithy.ValidationError](
            createOrganizationPostRequest.name,
            createOrganizationPostRequest.slug,
            createOrganizationPostRequest.tagline,
            createOrganizationPostRequest.emails,
            createOrganizationPostRequest.phoneNumbers,
            createOrganizationPostRequest.addresses,
            createOrganizationPostRequest.companyRegistrationNumber,
            createOrganizationPostRequest.taxID,
            Some(accessJwt.accessToken),
          )

          _ = createOrganizationPostResponse.code shouldBe StatusCode.BadRequest
          _ = createOrganizationPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name"))

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )
          organizationUserRowsAll <- postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting)

          _ = organizationDetailsRowsAll should have size 0
          _ = organizationUserRowsAll shouldBe empty

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0
        } yield ()
      }

      "fail with InternalServerError when organization slug already exists" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          createOrganizationPostRequest  = arbitrarySample[CreateOrganizationPostRequest]
          existingOrganizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
            .copy(slug = createOrganizationPostRequest.slug)

          _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(existingOrganizationDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          createOrganizationPostResponse <- gatewayClient.createOrganizationPost[smithy.InternalServerError](
            createOrganizationPostRequest.name,
            createOrganizationPostRequest.slug,
            createOrganizationPostRequest.tagline,
            createOrganizationPostRequest.emails,
            createOrganizationPostRequest.phoneNumbers,
            createOrganizationPostRequest.addresses,
            createOrganizationPostRequest.companyRegistrationNumber,
            createOrganizationPostRequest.taxID,
            Some(accessJwt.accessToken),
          )

          _ = createOrganizationPostResponse.code shouldBe StatusCode.InternalServerError
          _ = createOrganizationPostResponse.body.left.value shouldBe smithy.InternalServerError()

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )
          organizationUserRowsAll <- postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting)

          _ = organizationDetailsRowsAll shouldBe List(existingOrganizationDetailsRow)
          _ = organizationUserRowsAll shouldBe empty

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0
        } yield ()
      }
    }

    "middleware" should {
      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.createOrganizationPost[smithy.Unauthorized](
                createOrganizationPostRequest.name,
                createOrganizationPostRequest.slug,
                createOrganizationPostRequest.tagline,
                createOrganizationPostRequest.emails,
                createOrganizationPostRequest.phoneNumbers,
                createOrganizationPostRequest.addresses,
                createOrganizationPostRequest.companyRegistrationNumber,
                createOrganizationPostRequest.taxID,
                None,
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Unauthorized()))

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )
          organizationUserRowsAll <- postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting)

          _ = organizationDetailsRowsAll shouldBe empty
          _ = organizationUserRowsAll shouldBe empty

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.createOrganizationPost[smithy.Unauthorized](
                createOrganizationPostRequest.name,
                createOrganizationPostRequest.slug,
                createOrganizationPostRequest.tagline,
                createOrganizationPostRequest.emails,
                createOrganizationPostRequest.phoneNumbers,
                createOrganizationPostRequest.addresses,
                createOrganizationPostRequest.companyRegistrationNumber,
                createOrganizationPostRequest.taxID,
                Some(AccessToken("invalidtoken")),
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Unauthorized()))

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )
          organizationUserRowsAll <- postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting)

          _ = organizationDetailsRowsAll shouldBe empty
          _ = organizationUserRowsAll shouldBe empty

          mailHogResponse <- mailHogClient.readInbox()

          _ = mailHogResponse.total shouldBe 0
        } yield ()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStageInvalid <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.completedStages)
              .map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStageInvalid)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.createOrganizationPost[smithy.Forbidden](
                  createOrganizationPostRequest.name,
                  createOrganizationPostRequest.slug,
                  createOrganizationPostRequest.tagline,
                  createOrganizationPostRequest.emails,
                  createOrganizationPostRequest.phoneNumbers,
                  createOrganizationPostRequest.addresses,
                  createOrganizationPostRequest.companyRegistrationNumber,
                  createOrganizationPostRequest.taxID,
                  Some(accessJwt.accessToken),
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Forbidden()))

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )
            organizationUserRowsAll <- postgresClient.executeQuery(
              organizationUserQueries.getAllOrganizationUsersTesting
            )

            _ = organizationDetailsRowsAll shouldBe empty
            _ = organizationUserRowsAll shouldBe empty

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            userID                        = arbitrarySample[UserID]
            createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

            accessJwt <- jwtService.generateAccessToken(userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.createOrganizationPost[smithy.InternalServerError](
                  createOrganizationPostRequest.name,
                  createOrganizationPostRequest.slug,
                  createOrganizationPostRequest.tagline,
                  createOrganizationPostRequest.emails,
                  createOrganizationPostRequest.phoneNumbers,
                  createOrganizationPostRequest.addresses,
                  createOrganizationPostRequest.companyRegistrationNumber,
                  createOrganizationPostRequest.taxID,
                  Some(accessJwt.accessToken),
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.InternalServerError()))

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )
            organizationUserRowsAll <- postgresClient.executeQuery(
              organizationUserQueries.getAllOrganizationUsersTesting
            )

            _ = organizationDetailsRowsAll shouldBe empty
            _ = organizationUserRowsAll shouldBe empty

            mailHogResponse <- mailHogClient.readInbox()

            _ = mailHogResponse.total shouldBe 0
          } yield ()
      }
    }
  }
}
