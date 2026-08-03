package io.mesazon.gateway.it

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.it.client.GatewayClient
import io.mesazon.gateway.it.client.GatewayClient.given
import io.mesazon.gateway.it.harness.GatewayAcceptanceTest
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.*
import org.scalatest.DoNotDiscover
import sttp.model.*
import zio.*
import zio.stream.ZStream

@DoNotDiscover
class FileApiSpec extends GatewayAcceptanceTest, SmithyArbitraries, RepositoryArbitraries, IronRefinedTypeTransformer {

  // Mirrors the gateway container's organization-s3-client config (application.conf)
  private val organizationLogoBucket           = "organization-logo-bucket"
  private val organizationLogoBucketPathPrefix = "organization/logos"

  // Mirrors the gateway container's s3-client-organization-media config (application.conf)
  private val catalogueItemImageBucket           = "organization-media"
  private val catalogueItemImageBucketPathPrefix = "catalogue/item-images"

  "File Service API" when {
    "/upload/organization/logo" should {
      "upload the logo and store the original and normalized objects in S3" in withContext { context =>
        import context.*

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoOriginalBucketKey = None,
            logoNormalizedBucketKey = None,
            logoOriginalFileName = None,
          )

        postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          organizationID = organizationDetailsRow.organizationID,
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = organizationDetailsRow.organizationID
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Ok

        assert(uploadOrganizationLogoResponse.body.isRight)

        val expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

        val logoOriginalObjectBytes = s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/original")
          .zioValue

        val logoNormalizedObjectBytes = s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioValue

        logoOriginalObjectBytes shouldBe logoBytes
        logoNormalizedObjectBytes.size should be > 0

        val organizationDetailsRowUpdated = postgresClient
          .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
          .zioValue
          .head

        organizationDetailsRowUpdated.organizationStage shouldBe OrganizationStage.LogoProvided
        organizationDetailsRowUpdated.logoOriginalFileName shouldBe Some(organizationLogoOriginalFileName)
        organizationDetailsRowUpdated.logoOriginalBucketKey.map(_.value) shouldBe
          Some(s"$expectedBucketKeyPrefix/original")
        organizationDetailsRowUpdated.logoNormalizedBucketKey.map(_.value) shouldBe
          Some(s"$expectedBucketKeyPrefix/normalized")
      }

      "fail with BadRequest when the file name header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val logoBytes = ZStream.fromResource("assets/test-logo-1.jpeg").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.BadRequest
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.BadRequest](
            None,
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.BadRequest
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            Some(organizationID),
            Some(organizationLogoOriginalFileName),
            logoBytes,
            None,
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Unauthorized
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            Some(organizationID),
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Unauthorized
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Forbidden when the user is assigned to the organization with a disallowed role" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val organizationUserRoleDisallowed = Random
            .shuffle(
              OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles
            )
            .zioValue
            .head

          val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleDisallowed,
          )

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
          val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

          val uploadOrganizationLogoResponse = gatewayClient
            .uploadOrganizationLogoPost[smithy.Forbidden](
              Some(organizationUserRow.organizationID),
              Some(organizationLogoOriginalFileName),
              logoBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          uploadOrganizationLogoResponse.code shouldBe StatusCode.Forbidden
          uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Forbidden](
            Some(organizationID),
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Forbidden
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with InternalServerError when the user is not assigned to the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("test-logo-1.jpeg")
        val organizationID                   = arbitrarySample[OrganizationID]
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.InternalServerError
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with InternalServerError when the uploaded file is not a supported image" in withContext { context =>
        import context.*

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoOriginalBucketKey = None,
            logoNormalizedBucketKey = None,
            logoOriginalFileName = None,
          )

        postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoOriginalFileName = OrganizationLogoOriginalFileName.assume("malformed.png")
        val organizationID                   = organizationDetailsRow.organizationID
        val logoBytes = ZStream.fromResource(s"assets/${organizationLogoOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.InternalServerError
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.InternalServerError()

        val organizationDetailsRowUpdated = postgresClient
          .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
          .zioValue
          .head

        organizationDetailsRowUpdated.organizationStage shouldBe organizationDetailsRowUpdated.organizationStage
        organizationDetailsRowUpdated.logoOriginalFileName shouldBe None
        organizationDetailsRowUpdated.logoOriginalBucketKey shouldBe None
        organizationDetailsRowUpdated.logoNormalizedBucketKey shouldBe None

        val expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

        s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationLogoBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }
    }

    "/upload/catalogue-item/image" should {
      "upload the image and store the original and normalized objects in S3 with row updated" in withContext {
        context =>
          import context.*

          val catalogueItemRowActive = arbitrarySample[CatalogueItemRow]
            .copy(
              imageAsset = None,
              status = CatalogueItemStatus.Active,
            )

          postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowActive)).zioValue

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRowActive.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
          val organizationID                     = catalogueItemRowActive.organizationID
          val catalogueItemID                    = catalogueItemRowActive.catalogueItemID
          val imageBytes                         =
            ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

          val uploadCatalogueItemImageResponse = gatewayClient
            .uploadCatalogueItemImagePost[smithy.InternalServerError](
              Some(organizationID),
              Some(catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              imageBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          uploadCatalogueItemImageResponse.code shouldBe StatusCode.Ok

          assert(uploadCatalogueItemImageResponse.body.isRight)

          val expectedBucketKeyPrefix =
            s"$catalogueItemImageBucketPathPrefix/${organizationID.value}/${catalogueItemID.value}"

          val imageOriginalObjectBytes = s3TestClient
            .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/original")
            .zioValue

          val imageNormalizedObjectBytes = s3TestClient
            .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/normalized")
            .zioValue

          imageOriginalObjectBytes shouldBe imageBytes
          imageNormalizedObjectBytes.size should be > 0

          val catalogueItemRowUpdated = postgresClient
            .executeQuery(catalogueItemQueries.getCatalogueItemRow(organizationID, catalogueItemID))
            .zioValue

          catalogueItemRowUpdated.isDefined shouldBe true
          catalogueItemRowUpdated.get.imageAsset.isDefined shouldBe true
          catalogueItemRowUpdated.get.imageAsset.get.value.imageOriginalFileName shouldBe catalogueItemImageOriginalFileName
          catalogueItemRowUpdated.get.imageAsset.get.value.imageOriginalBucketKey.value shouldBe s"$expectedBucketKeyPrefix/original"
          catalogueItemRowUpdated.get.imageAsset.get.value.imageNormalizedBucketKey.value shouldBe s"$expectedBucketKeyPrefix/normalized"
          catalogueItemRowUpdated.get.status shouldBe CatalogueItemStatus.Active
      }

      "fail with BadRequest when the catalogue item ID header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.BadRequest
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with BadRequest when the file name header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val imageBytes      = ZStream.fromResource("assets/test-logo-1.jpeg").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueItemID),
            None,
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.BadRequest
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.BadRequest](
            None,
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.BadRequest
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.Unauthorized](
            Some(organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            None,
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.Unauthorized
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.Unauthorized](
            Some(organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.Unauthorized
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Forbidden when the user is assigned to the organization with a disallowed role" in withContext {
        context =>
          import context.*

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val organizationUserRoleDisallowed = Random
            .shuffle(
              OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles
            )
            .zioValue
            .head

          val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleDisallowed,
          )

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemID                    = arbitrarySample[CatalogueItemID]
          val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
          val imageBytes                         =
            ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

          val uploadCatalogueItemImageResponse = gatewayClient
            .uploadCatalogueItemImagePost[smithy.Forbidden](
              Some(organizationUserRow.organizationID),
              Some(catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              imageBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          uploadCatalogueItemImageResponse.code shouldBe StatusCode.Forbidden
          uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.Forbidden](
            Some(organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.Forbidden
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with InternalServerError when the user is not assigned to the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.InternalServerError](
            Some(organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with InternalServerError when the catalogue item is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with InternalServerError when the catalogue item is archived" in withContext { context =>
        import context.*

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow]
          .copy(
            status = CatalogueItemStatus.Archived,
            imageAsset = None,
          )

        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowArchived)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          organizationID = catalogueItemRowArchived.organizationID,
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.InternalServerError](
            Some(catalogueItemRowArchived.organizationID),
            Some(catalogueItemRowArchived.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowArchived.organizationID.value}/${catalogueItemRowArchived.catalogueItemID.value}"

        s3TestClient
          .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }

      "fail with InternalServerError when the uploaded file is not a supported image" in withContext { context =>
        import context.*

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow]
          .copy(
            imageAsset = None,
            status = CatalogueItemStatus.Active,
          )

        postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowActive)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          organizationID = catalogueItemRowActive.organizationID,
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("malformed.png")
        val organizationID                     = catalogueItemRowActive.organizationID
        val catalogueItemID                    = catalogueItemRowActive.catalogueItemID
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.InternalServerError](
            Some(organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()

        val catalogueItemRowUpdated = postgresClient
          .executeQuery(catalogueItemQueries.getCatalogueItemRow(organizationID, catalogueItemID))
          .zioValue

        catalogueItemRowUpdated.get.imageAsset shouldBe None

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${organizationID.value}/${catalogueItemID.value}"

        s3TestClient
          .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }

      "fail with InternalServerError when the catalogue item belongs to a different organization" in withContext {
        context =>
          import context.*

          val catalogueItemRowOrgA = arbitrarySample[CatalogueItemRow]
            .copy(
              imageAsset = None,
              status = CatalogueItemStatus.Active,
            )

          postgresClient.executeQuery(catalogueItemQueries.insertCatalogueItemRow(catalogueItemRowOrgA)).zioValue

          val onboardStage     = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow   = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationOrgB = arbitrarySample[OrganizationID]

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRowOrgB     = arbitrarySample[OrganizationUserRow].copy(
            organizationID = organizationOrgB,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRowOrgB)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("test-logo-1.jpeg")
          val imageBytes                         =
            ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

          val uploadCatalogueItemImageResponse = gatewayClient
            .uploadCatalogueItemImagePost[smithy.InternalServerError](
              Some(organizationOrgB),
              Some(catalogueItemRowOrgA.catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              imageBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
          uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()

          val expectedBucketKeyPrefix =
            s"$catalogueItemImageBucketPathPrefix/${organizationOrgB.value}/${catalogueItemRowOrgA.catalogueItemID.value}"

          s3TestClient
            .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/original")
            .zioEither
            .isLeft shouldBe true

          s3TestClient
            .getObject(catalogueItemImageBucket, s"$expectedBucketKeyPrefix/normalized")
            .zioEither
            .isLeft shouldBe true
      }

      // Oversized-upload rejection is covered at the unit level (FileScannerSpec) and the functional level
      // (FileServiceSpec "fail and stop the pipeline ... when scanning ... fails"). A real end-to-end HTTP
      // upload past the 20 MB transport limit is not exercised here — see known-issues.md
      // "Oversized Tapir upload can hang the request instead of failing fast".
    }
  }
}
