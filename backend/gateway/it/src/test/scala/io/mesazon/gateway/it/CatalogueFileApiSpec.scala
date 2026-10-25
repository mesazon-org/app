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
class CatalogueFileApiSpec
    extends GatewayAcceptanceTest,
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  private val organizationMediaBucket            = "organization-media"
  private val catalogueItemImageBucketPathPrefix = "catalogue/item-images"

  "Catalogue File Service API" when {
    "/catalogue/file/upload/image/item" should {
      "upload the image and store the original and normalized objects in S3 with row updated" in withContext {
        context =>
          import context.*

          val catalogueItemRow = arbitrarySample[CatalogueItemRow]
            .copy(
              isArchived = false
            )
          val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
            .zioValue

          val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          val organizationID                     = catalogueItemRow.organizationID
          val catalogueItemID                    = catalogueItemRow.catalogueItemID
          val imageBytes                         =
            ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

          val uploadCatalogueItemImageResponse = gatewayClient
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
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

          val catalogueItemRowsUpdated = postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            .zioValue

          catalogueItemRowsUpdated should have size 1

          val catalogueItemRowUpdated = catalogueItemRowsUpdated.head
          catalogueItemRowUpdated.copy(
            imageAsset = catalogueItemRow.imageAsset,
            updatedAt = catalogueItemRow.updatedAt,
          ) shouldBe catalogueItemRow
          catalogueItemRowUpdated.updatedAt should not be catalogueItemRow.updatedAt

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

          catalogueItemRowUpdated.imageAsset shouldBe Some(catalogueItemImageAssetExpected)
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

        val accessJwt        = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.BadRequest](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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

        val accessJwt        = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemID = arbitrarySample[CatalogueItemID]
        val imageBytes      = ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.BadRequest](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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

        val accessJwt        = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.BadRequest](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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

        val catalogueItemRow = arbitrarySample[CatalogueItemRow]
          .copy(
            imageAsset = None,
            isArchived = false,
          )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          organizationID = catalogueItemRow.organizationID,
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )

        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-malformed-it-1.png")
        val organizationID                     = catalogueItemRow.organizationID
        val catalogueItemID                    = catalogueItemRow.catalogueItemID
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.BadRequest](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

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
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.Unauthorized](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.Unauthorized](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationUserRow.organizationID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes                         =
          ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.Forbidden](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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

        val accessJwt        = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemID  = arbitrarySample[CatalogueItemID]
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          organizationID = organizationID,
          userID = userDetailsRow.userID,
          userRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes                         =
          ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.Forbidden](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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

        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          isArchived = false,
        )
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          organizationID = organizationID,
          userID = userID,
          userRole = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userID).zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.InternalServerError](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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

        val accessJwt        = jwtService.generateAccessToken(userDetailsRow.userID).zioValue
        val catalogueItemID  = arbitrarySample[CatalogueItemID]
        val organizationID   = arbitrarySample[OrganizationID]
        val catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          imageAsset = None,
          isArchived = false,
        )
        val catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRow.organizationID,
          catalogueItemID = catalogueItemRow.catalogueItemID,
          isArchived = false,
        )

        postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow)).zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow)))
          .zioValue

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.InternalServerError](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRow)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRow)

        val expectedBucketKeyPrefix =
          s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"

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
          .catalogueFileUploadImageItemPost[smithy.InternalServerError](
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
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
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

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow]
          .copy(
            isArchived = true,
            imageAsset = None,
          )
        val catalogueItemVariantRowArchivedItem = arbitrarySample[CatalogueItemVariantRow].copy(
          organizationID = catalogueItemRowArchived.organizationID,
          catalogueItemID = catalogueItemRowArchived.catalogueItemID,
          isArchived = false,
        )

        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          .zioValue
        postgresClient
          .executeQuery(catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowArchivedItem)))
          .zioValue

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

        val catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
        val imageBytes = ZStream.fromResource(s"assets/${catalogueItemImageOriginalFileName.value}").runCollect.zioValue

        val uploadCatalogueItemImageResponse = gatewayClient
          .catalogueFileUploadImageItemPost[smithy.InternalServerError](
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
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
          .zioEither
          .isLeft shouldBe true

        s3TestClient
          .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
          .zioEither
          .isLeft shouldBe true

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          .zioValue shouldBe List(catalogueItemRowArchived)

        postgresClient
          .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
          .zioValue shouldBe List(catalogueItemVariantRowArchivedItem)
      }

      "fail with InternalServerError when the catalogue item belongs to a different organization" in withContext {
        context =>
          import context.*

          val catalogueItemRowOrgA = arbitrarySample[CatalogueItemRow]
            .copy(
              imageAsset = None,
              isArchived = false,
            )
          val catalogueItemVariantRowOrgA = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRowOrgA.organizationID,
            catalogueItemID = catalogueItemRowOrgA.catalogueItemID,
            isArchived = false,
          )

          postgresClient
            .executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowOrgA))
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
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
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
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
            .zioEither
            .isLeft shouldBe true

          s3TestClient
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
            .zioEither
            .isLeft shouldBe true

          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            .zioValue shouldBe List(catalogueItemRowOrgA)

          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
            .zioValue shouldBe List(catalogueItemVariantRowOrgA)
      }

      // Oversized-upload rejection is covered at the unit level (FileScannerSpec) and the functional level
      // (FileServiceSpec "fail and stop the pipeline ... when scanning ... fails"). A real end-to-end HTTP
      // upload past the 20 MB transport limit is not exercised here — see known-issues.md
      // "Oversized Tapir upload can hang the request instead of failing fast".
    }

    "/catalogue/file/ai/extract" should {
      "extract item candidates from an image without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue
        val catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = CatalogueItemName.assume("Bolt"),
              description = None,
              variants = List(
                CatalogueItemVariantInsert(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = Map(
                    CatalogueItemVariantAttributeName.assume("Color") ->
                      CatalogueItemVariantAttributeValue.assume("Silver")
                  ),
                  price = None,
                  sellingUnit = CatalogueItemVariantSaleUnit(
                    quantity = CatalogueItemVariantQuantity(
                      value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                      unit = CatalogueItemVariantUnit.PerUnit.Piece,
                    ),
                    perUnit = None,
                    unitSize = None,
                  ),
                )
              ),
              needsAttention = false,
              extractionNotes = List.empty,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List.empty,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        catalogueFileAiExtractPostResponse.body shouldBe Right(catalogueFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract item candidates from CSV without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-1.csv")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue
        val catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = CatalogueItemName.assume("Bolt"),
              description = None,
              variants = List(
                CatalogueItemVariantInsert(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = Map(
                    CatalogueItemVariantAttributeName.assume("Color") ->
                      CatalogueItemVariantAttributeValue.assume("Silver")
                  ),
                  price = None,
                  sellingUnit = CatalogueItemVariantSaleUnit(
                    quantity = CatalogueItemVariantQuantity(
                      value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                      unit = CatalogueItemVariantUnit.PerUnit.Piece,
                    ),
                    perUnit = None,
                    unitSize = None,
                  ),
                )
              ),
              needsAttention = false,
              extractionNotes = List.empty,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List.empty,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        catalogueFileAiExtractPostResponse.body shouldBe Right(catalogueFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract item candidates from Excel without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-2.xlsx")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue
        val catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = CatalogueItemName.assume("Bolt"),
              description = None,
              variants = List(
                CatalogueItemVariantInsert(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = Map(
                    CatalogueItemVariantAttributeName.assume("Color") ->
                      CatalogueItemVariantAttributeValue.assume("Silver")
                  ),
                  price = None,
                  sellingUnit = CatalogueItemVariantSaleUnit(
                    quantity = CatalogueItemVariantQuantity(
                      value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                      unit = CatalogueItemVariantUnit.PerUnit.Piece,
                    ),
                    perUnit = None,
                    unitSize = None,
                  ),
                )
              ),
              needsAttention = false,
              extractionNotes = List.empty,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List.empty,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        catalogueFileAiExtractPostResponse.body shouldBe Right(catalogueFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "extract item candidates from XLS without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-3.xls")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue
        val catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = CatalogueItemName.assume("Bolt"),
              description = None,
              variants = List(
                CatalogueItemVariantInsert(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = Map(
                    CatalogueItemVariantAttributeName.assume("Color") ->
                      CatalogueItemVariantAttributeValue.assume("Silver")
                  ),
                  price = None,
                  sellingUnit = CatalogueItemVariantSaleUnit(
                    quantity = CatalogueItemVariantQuantity(
                      value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                      unit = CatalogueItemVariantUnit.PerUnit.Piece,
                    ),
                    perUnit = None,
                    unitSize = None,
                  ),
                )
              ),
              needsAttention = false,
              extractionNotes = List.empty,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List.empty,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        catalogueFileAiExtractPostResponse.body shouldBe Right(catalogueFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "accept processing guidance at the length limit after trimming" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue
        val processingMessageMaxLength                 = 2000
        val catalogueFileProcessingMessage             = s"  ${"a" * processingMessageMaxLength}  "
        val catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
          candidates = List(
            CatalogueItemCandidate(
              name = CatalogueItemName.assume("Bolt"),
              description = None,
              variants = List(
                CatalogueItemVariantInsert(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = Map(
                    CatalogueItemVariantAttributeName.assume("Color") ->
                      CatalogueItemVariantAttributeValue.assume("Silver")
                  ),
                  price = None,
                  sellingUnit = CatalogueItemVariantSaleUnit(
                    quantity = CatalogueItemVariantQuantity(
                      value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                      unit = CatalogueItemVariantUnit.PerUnit.Piece,
                    ),
                    perUnit = None,
                    unitSize = None,
                  ),
                )
              ),
              needsAttention = false,
              extractionNotes = List.empty,
            )
          ),
          infoNotes = List("Processed 1 of 1 entries"),
          extractionNotes = List.empty,
        )

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(catalogueFileProcessingMessage),
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
        catalogueFileAiExtractPostResponse.body shouldBe Right(catalogueFileAiExtractPostResponseExpected)

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with BadRequest when the file name header is missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with BadRequest when the organization id header is missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.BadRequest](
            None,
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with BadRequest when processing guidance is too long without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource(s"assets/${catalogueFileExtractFileName.value}").runCollect.zioValue
        val processingMessageOverMaxLength = 2001
        val catalogueFileProcessingMessage = "a" * processingMessageOverMaxLength

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(catalogueFileProcessingMessage),
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with BadRequest when the file name has no extension without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with BadRequest when the file extension is unsupported without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image.pdf")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with BadRequest when the filename and content disagree without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image.png")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with Unauthorized when the access token is missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.Unauthorized](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            None,
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Unauthorized
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with Unauthorized when the access token is invalid without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.Unauthorized](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(AccessToken("invalidtoken")),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Unauthorized
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.Unauthorized()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with Forbidden when the organization role is disallowed without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed =
          Random.shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.Forbidden](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Forbidden
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with Forbidden when onboarding is incomplete without storing data" in withContext { context =>
        import context.*

        val onboardStage   = Random.shuffle(OnboardStage.values.toList diff OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.Forbidden](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Forbidden
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.Forbidden()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with InternalServerError when user details are missing without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with InternalServerError when organization membership is missing without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          val catalogueFileExtractFileBytes =
            ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueFileAiExtractPostResponse = gatewayClient
            .catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )
            .zioValue

          catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with InternalServerError when the Excel upload is not a workbook without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue.xlsx")
          val catalogueFileExtractFileBytes =
            ZStream.fromResource("assets/catalogue-compressed-it-1.zip").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueFileAiExtractPostResponse = gatewayClient
            .catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )
            .zioValue

          catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with InternalServerError when image AI extraction fails without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some("AI_CLIENT_SPEC_ERROR"),
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with InternalServerError when the AI result is malformed without storing data" in withContext { context =>
        import context.*

        val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
        val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
        val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
          userID = userDetailsRow.userID,
          userRole = organizationUserRoleAllowed,
        )
        val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
        val catalogueFileExtractFileBytes =
          ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect.zioValue

        postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
        postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

        val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

        val catalogueFileAiExtractPostResponse = gatewayClient
          .catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some("AI_CLIENT_SPEC_MALFORMED"),
          )
          .zioValue

        catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
        catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
        postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting).zioValue shouldBe List.empty
        s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

      "fail with InternalServerError when spreadsheet AI extraction fails without storing data" in withContext {
        context =>
          import context.*

          val onboardStage                = Random.shuffle(OnboardStage.completedStages).zioValue.head
          val userDetailsRow              = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          val organizationUserRoleAllowed = Random.shuffle(OrganizationUserRole.adminRoles).zioValue.head
          val organizationUserRow         = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )
          val catalogueFileExtractFileName  = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-1.csv")
          val catalogueFileExtractFileBytes =
            ZStream.fromResource("assets/catalogue-spreadsheet-it-1.csv").runCollect.zioValue

          postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow)).zioValue
          postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow)).zioValue

          val accessJwt = jwtService.generateAccessToken(userDetailsRow.userID).zioValue

          val catalogueFileAiExtractPostResponse = gatewayClient
            .catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some("AI_CLIENT_SPEC_ERROR"),
            )
            .zioValue

          catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting).zioValue shouldBe List.empty
          postgresClient
            .executeQuery(catalogueQueries.getAllCatalogueItemVariantRowsTesting)
            .zioValue shouldBe List.empty
          s3TestClient.listObjectKeys(organizationMediaBucket).zioValue shouldBe List.empty
      }

    }
  }
}
