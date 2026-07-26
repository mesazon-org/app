package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*

@DoNotDiscover
class OrganizationManagementApiSpec
    extends GatewayAcceptanceTest,
      OrganizationManagementSmithyArbitraries,
      RepositoryArbitraries {

  "Organization Management Service API" when {
    "POST /create/organization" should {
      "successfully create organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.InternalServerError](
              createOrganizationPostRequest.name,
              createOrganizationPostRequest.slug,
              createOrganizationPostRequest.tagline,
              createOrganizationPostRequest.emails,
              createOrganizationPostRequest.phoneNumbers,
              createOrganizationPostRequest.addressLine1,
              createOrganizationPostRequest.addressLine2,
              createOrganizationPostRequest.city,
              createOrganizationPostRequest.postalCode,
              createOrganizationPostRequest.country,
              createOrganizationPostRequest.companyRegistrationNumber,
              createOrganizationPostRequest.taxID,
              Some(accessJwt.accessToken),
            )
            .zioValue

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 1
        organizationDetailsRowsAll.head shouldBe OrganizationDetailsRow(
          organizationID = organizationDetailsRowsAll.head.organizationID,
          name = createOrganizationPostRequest.name,
          slug = createOrganizationPostRequest.slug,
          tagline = createOrganizationPostRequest.tagline,
          emails = createOrganizationPostRequest.emails,
          phoneNumbers = createOrganizationPostRequest.phoneNumbers,
          organizationStage = OrganizationStage.DetailsProvided,
          addressLine1 = createOrganizationPostRequest.addressLine1,
          addressLine2 = createOrganizationPostRequest.addressLine2,
          city = createOrganizationPostRequest.city,
          postalCode = createOrganizationPostRequest.postalCode,
          country = createOrganizationPostRequest.country,
          companyRegistrationNumber = createOrganizationPostRequest.companyRegistrationNumber,
          taxID = createOrganizationPostRequest.taxID,
          logoOriginalBucketKey = None,
          logoNormalizedBucketKey = None,
          logoOriginalFileName = None,
          createdAt = organizationDetailsRowsAll.head.createdAt,
          updatedAt = organizationDetailsRowsAll.head.updatedAt,
        )

        createOrganizationPostResponse.code shouldBe StatusCode.Ok
        createOrganizationPostResponse.body.value shouldBe smithy.CreateOrganizationPostResponse(organizationID =
          organizationDetailsRowsAll.head.organizationID.value
        )

        mailHogClient.readInbox().zioValue.total shouldBe 1

        val organizationUserRowsAll =
          postgresClient.executeQuery(organizationUserQueries.getAllOrganizationUsersTesting).zioValue

        organizationUserRowsAll should have size 1
        organizationUserRowsAll.head shouldBe OrganizationUserRow(
          organizationDetailsRowsAll.head.organizationID,
          userDetailsRow.userID,
          OrganizationUserRole.Owner,
          organizationUserRowsAll.head.createdAt,
          organizationUserRowsAll.head.updatedAt,
        )
      }

      "fail with BadRequest ValidationError when request is invalid" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]
          .copy(name = OrganizationName.assume(""))

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.ValidationError](
              createOrganizationPostRequest.name,
              createOrganizationPostRequest.slug,
              createOrganizationPostRequest.tagline,
              createOrganizationPostRequest.emails,
              createOrganizationPostRequest.phoneNumbers,
              createOrganizationPostRequest.addressLine1,
              createOrganizationPostRequest.addressLine2,
              createOrganizationPostRequest.city,
              createOrganizationPostRequest.postalCode,
              createOrganizationPostRequest.country,
              createOrganizationPostRequest.companyRegistrationNumber,
              createOrganizationPostRequest.taxID,
              Some(accessJwt.accessToken),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.BadRequest
        createOrganizationPostResponse.body.left.value shouldBe smithy.ValidationError(fields = List("name"))

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.Unauthorized](
              createOrganizationPostRequest.name,
              createOrganizationPostRequest.slug,
              createOrganizationPostRequest.tagline,
              createOrganizationPostRequest.emails,
              createOrganizationPostRequest.phoneNumbers,
              createOrganizationPostRequest.addressLine1,
              createOrganizationPostRequest.addressLine2,
              createOrganizationPostRequest.city,
              createOrganizationPostRequest.postalCode,
              createOrganizationPostRequest.country,
              createOrganizationPostRequest.companyRegistrationNumber,
              createOrganizationPostRequest.taxID,
              None,
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.Unauthorized
        createOrganizationPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.Unauthorized](
              createOrganizationPostRequest.name,
              createOrganizationPostRequest.slug,
              createOrganizationPostRequest.tagline,
              createOrganizationPostRequest.emails,
              createOrganizationPostRequest.phoneNumbers,
              createOrganizationPostRequest.addressLine1,
              createOrganizationPostRequest.addressLine2,
              createOrganizationPostRequest.city,
              createOrganizationPostRequest.postalCode,
              createOrganizationPostRequest.country,
              createOrganizationPostRequest.companyRegistrationNumber,
              createOrganizationPostRequest.taxID,
              Some(AccessToken("invalidtoken")),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.Unauthorized
        createOrganizationPostResponse.body.left.value shouldBe smithy.Unauthorized()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.Forbidden](
              createOrganizationPostRequest.name,
              createOrganizationPostRequest.slug,
              createOrganizationPostRequest.tagline,
              createOrganizationPostRequest.emails,
              createOrganizationPostRequest.phoneNumbers,
              createOrganizationPostRequest.addressLine1,
              createOrganizationPostRequest.addressLine2,
              createOrganizationPostRequest.city,
              createOrganizationPostRequest.postalCode,
              createOrganizationPostRequest.country,
              createOrganizationPostRequest.companyRegistrationNumber,
              createOrganizationPostRequest.taxID,
              Some(accessJwt.accessToken),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.Forbidden
        createOrganizationPostResponse.body.left.value shouldBe smithy.Forbidden()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 0
      }

      "fail with InternalServerError when organization slug already exists" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val createOrganizationPostRequest = arbitrarySample[CreateOrganizationPostRequest]

        val existingOrganizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(slug = createOrganizationPostRequest.slug)

        postgresClient.executeQuery(organizationDetailsQueries.insert(existingOrganizationDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val createOrganizationPostResponse =
          gatewayClient
            .createOrganizationPost[smithy.InternalServerError](
              createOrganizationPostRequest.name,
              createOrganizationPostRequest.slug,
              createOrganizationPostRequest.tagline,
              createOrganizationPostRequest.emails,
              createOrganizationPostRequest.phoneNumbers,
              createOrganizationPostRequest.addressLine1,
              createOrganizationPostRequest.addressLine2,
              createOrganizationPostRequest.city,
              createOrganizationPostRequest.postalCode,
              createOrganizationPostRequest.country,
              createOrganizationPostRequest.companyRegistrationNumber,
              createOrganizationPostRequest.taxID,
              Some(accessJwt.accessToken),
            )
            .zioValue

        createOrganizationPostResponse.code shouldBe StatusCode.InternalServerError
        createOrganizationPostResponse.body.left.value shouldBe smithy.InternalServerError()

        val organizationDetailsRowsAll =
          postgresClient.executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting).zioValue
        organizationDetailsRowsAll should have size 1

        mailHogClient.readInbox().zioValue.total shouldBe 0
      }
    }
  }
}
