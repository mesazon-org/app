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
class FileApiSpec
    extends GatewayAcceptanceTest,
      SmithyArbitraries,
      RepositoryArbitraries,
      CatalogueRepositoryV2Arbitraries,
      IronRefinedTypeTransformer {

  // Mirrors the gateway container's s3-client-organization-media config (application.conf)
  private val organizationMediaBucket            = "organization-media"
  private val organizationLogoBucketPathPrefix   = "organization/logos"
  private val catalogueItemImageBucketPathPrefix = "catalogue/item-images"

  "File Service API" when {
    "/upload/organization/logo" should {
      "upload the logo and store the original and normalized objects in S3" in withContext { context =>
        import context.*

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoImageAsset = None
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

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
        val organizationID                        = organizationDetailsRow.organizationID
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Ok

        assert(uploadOrganizationLogoResponse.body.isRight)

        val expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

        val logoOriginalObjectBytes = s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioValue

        val logoNormalizedObjectBytes = s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioValue

        logoOriginalObjectBytes shouldBe logoBytes
        logoNormalizedObjectBytes.size should be > 0

        val organizationDetailsRowUpdated = postgresClient
          .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
          .zioValue
          .head

        organizationDetailsRowUpdated.organizationStage shouldBe OrganizationStage.LogoProvided

        val logoImageAssetValue = organizationDetailsRowUpdated.logoImageAsset.value.value

        logoImageAssetValue.imageOriginalFileName shouldBe organizationLogoImageOriginalFileName
        logoImageAssetValue.imageOriginalS3BucketKey.value shouldBe s"$expectedBucketKeyPrefix/original"
        logoImageAssetValue.imageNormalizedS3BucketKey.value shouldBe s"$expectedBucketKeyPrefix/normalized"
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

        val logoBytes = ZStream.fromResource("assets/organization-image-it-1.jpeg").runCollect.zioValue

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

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.BadRequest](
            None,
            Some(organizationLogoImageOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.BadRequest
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
        val organizationID                        = arbitrarySample[OrganizationID]
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
            logoBytes,
            None,
          )
          .zioValue

        uploadOrganizationLogoResponse.code shouldBe StatusCode.Unauthorized
        uploadOrganizationLogoResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
        val organizationID                        = arbitrarySample[OrganizationID]
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Unauthorized](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
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

          val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
          val logoBytes                             =
            ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

          val uploadOrganizationLogoResponse = gatewayClient
            .uploadOrganizationLogoPost[smithy.Forbidden](
              Some(organizationUserRow.organizationID),
              Some(organizationLogoImageOriginalFileName),
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

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
        val organizationID                        = arbitrarySample[OrganizationID]
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.Forbidden](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
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

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
        val organizationID                        = arbitrarySample[OrganizationID]
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
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
            logoImageAsset = None
          )

        postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow)).zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-malformed-it-1.png")
        val organizationID                        = organizationDetailsRow.organizationID
        val logoBytes                             =
          ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect.zioValue

        val uploadOrganizationLogoResponse = gatewayClient
          .uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
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
        organizationDetailsRowUpdated.logoImageAsset shouldBe None

        val expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }
    }

    "/upload/catalogue-item/image" should {
      "upload the image and store the original and normalized objects in S3 with row updated" in withContext {
        context =>
          import context.*

          val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2]
            .copy(
              imageAsset = None,
              status = CatalogueItemStatus.Active,
            )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRowV2.organizationID,
            catalogueItemID = catalogueItemRowV2.catalogueItemID,
            status = CatalogueItemVariantStatus.Active,
          )
          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
            .zioValue

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRowV2.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          val organizationID                     = catalogueItemRowV2.organizationID
          val catalogueItemID                    = catalogueItemRowV2.catalogueItemID
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
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
            .zioValue

          val imageNormalizedObjectBytes = s3TestClient
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
            .zioValue

          imageOriginalObjectBytes shouldBe imageBytes
          imageNormalizedObjectBytes.size should be > 0

          val catalogueItemRowsV2Updated = postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
            .zioValue

          catalogueItemRowsV2Updated should have size 1

          val catalogueItemRowV2Updated = catalogueItemRowsV2Updated.head
          catalogueItemRowV2Updated.copy(
            imageAsset = None,
            updatedAt = catalogueItemRowV2.updatedAt,
          ) shouldBe catalogueItemRowV2
          catalogueItemRowV2Updated.updatedAt should not be catalogueItemRowV2.updatedAt

          val catalogueItemVariantRowsUpdated = postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
            .zioValue

          catalogueItemVariantRowsUpdated shouldBe List(catalogueItemVariantRow)

          val catalogueItemImageAssetExpected = CatalogueItemImageAsset.assume(
            ImageAsset(
              imageOriginalS3BucketKey = ImageOriginalS3BucketKey.assume(s"$expectedBucketKeyPrefix/original"),
              imageNormalizedS3BucketKey = ImageNormalizedS3BucketKey.assume(s"$expectedBucketKeyPrefix/normalized"),
              imageOriginalFileName = catalogueItemImageOriginalFileName,
            )
          )

          catalogueItemRowV2Updated.imageAsset shouldBe Some(catalogueItemImageAssetExpected)
          catalogueItemRowV2Updated.status shouldBe CatalogueItemStatus.Active
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

        val accessJwt          = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

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

        val accessJwt          = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val imageBytes      = ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

      }

      "fail with BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt          = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

      }

      "fail with BadRequest when the uploaded file does not match its image filename" in withContext { context =>
        import context.*

        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2]
          .copy(
            imageAsset = None,
            status = CatalogueItemStatus.Active,
          )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-malformed-it-1.png")
        val organizationID                     = catalogueItemRowV2.organizationID
        val catalogueItemID                    = catalogueItemRowV2.catalogueItemID
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.BadRequest](
            Some(organizationID),
            Some(catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.BadRequest
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${organizationID.value}/${catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

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
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt          = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemID    = arbitrarySample[CatalogueItemID]
        val organizationID     = arbitrarySample[OrganizationID]
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          organizationID = organizationID,
          userID = userDetailsRow.userID,
          userRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes                         =
          ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }

      "fail with an InternalServerError when user details do not exist" in withContext { context =>
        import context.*

        val userID                             = arbitrarySample[UserID]
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes                         =
          ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          organizationID = organizationID,
          userID = userID,
          userRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

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

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true
      }

      "fail with InternalServerError when the user is not assigned to the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt          = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemID    = arbitrarySample[CatalogueItemID]
        val organizationID     = arbitrarySample[OrganizationID]
        val catalogueItemRowV2 = arbitrarySample[CatalogueItemRowV2].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          status = CatalogueItemStatus.Active,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2.organizationID,
          catalogueItemID = catalogueItemRowV2.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2.organizationID.value}/${catalogueItemRowV2.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

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
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
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
        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe Nil

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe Nil

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${organizationUserRow.organizationID.value}/${catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

      }

      "fail with InternalServerError when the catalogue item is archived" in withContext { context =>
        import context.*

        val catalogueItemRowV2Archived = arbitrarySample[CatalogueItemRowV2]
          .copy(
            status = CatalogueItemStatus.Archived,
            imageAsset = None,
          )
        val catalogueItemVariantRowArchivedItem = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowV2Archived.organizationID,
          catalogueItemID = catalogueItemRowV2Archived.catalogueItemID,
          status = CatalogueItemVariantStatus.Active,
        )

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2Archived))
          .zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchivedItem)))
          .zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          organizationID = catalogueItemRowV2Archived.organizationID,
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .uploadCatalogueItemImagePost[smithy.InternalServerError](
            Some(catalogueItemRowV2Archived.organizationID),
            Some(catalogueItemRowV2Archived.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            imageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
        uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRowV2Archived.organizationID.value}/${catalogueItemRowV2Archived.catalogueItemID.value}"

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
          .zioValue shouldBe List(catalogueItemRowV2Archived)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRowArchivedItem)
      }

      "fail with InternalServerError when the catalogue item belongs to a different organization" in withContext {
        context =>
          import context.*

          val catalogueItemRowV2OrgA = arbitrarySample[CatalogueItemRowV2]
            .copy(
              imageAsset = None,
              status = CatalogueItemStatus.Active,
            )
          val catalogueItemVariantRowOrgA = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRowV2OrgA.organizationID,
            catalogueItemID = catalogueItemRowV2OrgA.catalogueItemID,
            status = CatalogueItemVariantStatus.Active,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRowV2(catalogueItemRowV2OrgA))
            .zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowOrgA)))
            .zioValue

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

          val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          val imageBytes                         =
            ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

          val uploadCatalogueItemImageResponse = gatewayClient
            .uploadCatalogueItemImagePost[smithy.InternalServerError](
              Some(organizationOrgB),
              Some(catalogueItemRowV2OrgA.catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              imageBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          uploadCatalogueItemImageResponse.code shouldBe StatusCode.InternalServerError
          uploadCatalogueItemImageResponse.body.left.value shouldBe smithy.InternalServerError()

          val expectedBucketKeyPrefix =
            s"$catalogueItemImageBucketPathPrefix/${organizationOrgB.value}/${catalogueItemRowV2OrgA.catalogueItemID.value}"

          s3TestClient
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
            .zioEither
            .isLeft shouldBe true

          s3TestClient
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
            .zioEither
            .isLeft shouldBe true

          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemRowsV2Testing)
            .zioValue shouldBe List(catalogueItemRowV2OrgA)

          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
            .zioValue shouldBe List(catalogueItemVariantRowOrgA)
      }

      // Oversized-upload rejection is covered at the unit level (FileScannerSpec) and the functional level
      // (FileServiceSpec "fail and stop the pipeline ... when scanning ... fails"). A real end-to-end HTTP
      // upload past the 20 MB transport limit is not exercised here — see known-issues.md
      // "Oversized Tapir upload can hang the request instead of failing fast".
    }

    "/extract/customers" should {
      "extract customer candidates from an image" in withContext { context =>
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

        val customerBookImageBytes = ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(ExtractCustomersFileName.assume("customers.jpeg")),
            customerBookImageBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.Ok

        val extractCustomerIndividualDataExpected = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Jane Doe"),
            emails = List(
              ExtractCustomerEmailEntry(email = CustomerEmail.assume("jane.doe@example.com"), isDefault = true)
            ),
            phoneNumbers = List(
              ExtractCustomerPhoneNumberEntry(
                phoneNumber = ExtractCustomerPhoneNumber(
                  phoneNationalNumber = PhoneNationalNumber.assume("5551234567"),
                  phoneCountryCode = PhoneCountryCode.assume("+1"),
                ),
                isDefault = true,
              )
            ),
            addresses = List(
              CustomerAddressEntry(
                AddressEntry(
                  address = Address(
                    addressLine1 = Some(AddressLine1.assume("1 Main Street")),
                    addressLine2 = None,
                    city = Some(City.assume("Springfield")),
                    postalCode = Some(PostalCode.assume("62701")),
                    country = Some(Country.assume("United States")),
                  ),
                  addressType = AddressType.ShippingAndBilling,
                )
              )
            ),
          ),
          isDuplicate = false,
          extractionNotes = None,
        )

        extractCustomersPostResponse.body shouldBe Right(
          ExtractCustomersPostResponse(
            entriesIdentified = 1L,
            entriesProcessed = 1L,
            customerIndividualCandidates = List(extractCustomerIndividualDataExpected),
            customerBusinessCandidates = List.empty,
            emptyEntryRows = List.empty,
            unidentifiedEntryRows = List.empty,
            unidentifiedEntriesNotes = None,
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 0
      }

      "extract customer candidates from a CSV file" in withContext { context =>
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

        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.Ok

        val extractCustomerIndividualDataExpected = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("John Smith"),
            emails = List(
              ExtractCustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
            ),
            phoneNumbers = List.empty,
            addresses = List.empty,
          ),
          isDuplicate = false,
          extractionNotes = None,
        )

        extractCustomersPostResponse.body shouldBe Right(
          ExtractCustomersPostResponse(
            entriesIdentified = 1L,
            entriesProcessed = 1L,
            customerIndividualCandidates = List(extractCustomerIndividualDataExpected),
            customerBusinessCandidates = List.empty,
            emptyEntryRows = List.empty,
            unidentifiedEntryRows = List.empty,
            unidentifiedEntriesNotes = None,
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 0
      }

      "extract customer candidates from an Excel file" in withContext { context =>
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

        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-2.xlsx").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(ExtractCustomersFileName.assume("customers.xlsx")),
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.Ok

        val extractCustomerIndividualDataExpected = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("John Smith"),
            emails = List(
              ExtractCustomerEmailEntry(email = CustomerEmail.assume("john.smith@example.com"), isDefault = true)
            ),
            phoneNumbers = List.empty,
            addresses = List.empty,
          ),
          isDuplicate = false,
          extractionNotes = None,
        )

        extractCustomersPostResponse.body shouldBe Right(
          ExtractCustomersPostResponse(
            entriesIdentified = 1L,
            entriesProcessed = 1L,
            customerIndividualCandidates = List(extractCustomerIndividualDataExpected),
            customerBusinessCandidates = List.empty,
            emptyEntryRows = List.empty,
            unidentifiedEntryRows = List.empty,
            unidentifiedEntriesNotes = None,
          )
        )

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 0
      }

      "fail with BadRequest when the organization id header is missing" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.BadRequest](
            None,
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.BadRequest
        extractCustomersPostResponse.body.left.value shouldBe smithy.BadRequest()
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

        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.BadRequest
        extractCustomersPostResponse.body.left.value shouldBe smithy.BadRequest()
      }

      "fail with Unauthorized when access token is missing" in withContext { context =>
        import context.*

        val organizationID        = arbitrarySample[OrganizationID]
        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.Unauthorized](
            Some(organizationID),
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            None,
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.Unauthorized
        extractCustomersPostResponse.body.left.value shouldBe smithy.Unauthorized()
      }

      "fail with Unauthorized when access token is invalid" in withContext { context =>
        import context.*

        val organizationID        = arbitrarySample[OrganizationID]
        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.Unauthorized](
            Some(organizationID),
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            Some(AccessToken("invalidtoken")),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.Unauthorized
        extractCustomersPostResponse.body.left.value shouldBe smithy.Unauthorized()
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

          val customerBookFileBytes =
            ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

          val extractCustomersPostResponse = gatewayClient
            .extractCustomersPost[smithy.Forbidden](
              Some(organizationUserRow.organizationID),
              Some(ExtractCustomersFileName.assume("customers.csv")),
              customerBookFileBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          extractCustomersPostResponse.code shouldBe StatusCode.Forbidden
          extractCustomersPostResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with Forbidden when user is not in an allowed onboard stage" in withContext { context =>
        import context.*

        val onboardStageInvalid =
          Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationID        = arbitrarySample[OrganizationID]
        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.Forbidden](
            Some(organizationID),
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.Forbidden
        extractCustomersPostResponse.body.left.value shouldBe smithy.Forbidden()
      }

      "fail with InternalServerError when the user is not assigned to the organization" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val organizationID        = arbitrarySample[OrganizationID]
        val customerBookFileBytes =
          ZStream.fromResource("assets/contact-book-spreadsheet-it-1.csv").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.InternalServerError](
            Some(organizationID),
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.InternalServerError
        extractCustomersPostResponse.body.left.value shouldBe smithy.InternalServerError()
      }

      "fail with BadRequest when the file name extension does not match the uploaded file" in withContext { context =>
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

        val customerBookFileBytes = ZStream.fromResource("assets/contact-book-image-it-1.jpeg").runCollect.zioValue

        val extractCustomersPostResponse = gatewayClient
          .extractCustomersPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(ExtractCustomersFileName.assume("customers.csv")),
            customerBookFileBytes,
            Some(accessJwt.accessToken),
          )
          .zioValue

        extractCustomersPostResponse.code shouldBe StatusCode.BadRequest
        extractCustomersPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 0
      }

      "fail with InternalServerError when the uploaded file is a plain zip file accepted as a plausible Excel candidate but not genuinely a workbook" in withContext {
        context =>
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

          val customerBookFileBytes =
            ZStream.fromResource("assets/contact-book-compressed-it-1.zip").runCollect.zioValue

          val extractCustomersPostResponse = gatewayClient
            .extractCustomersPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(ExtractCustomersFileName.assume("customers.xlsx")),
              customerBookFileBytes,
              Some(accessJwt.accessToken),
            )
            .zioValue

          extractCustomersPostResponse.code shouldBe StatusCode.InternalServerError
          extractCustomersPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(customerBookQueries.getAllCustomerIDsTesting).zioValue should have size 0
      }
    }
  }
}
