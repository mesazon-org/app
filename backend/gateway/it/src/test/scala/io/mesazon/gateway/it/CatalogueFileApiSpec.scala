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
import zio.stream.ZStream

import java.time.Instant
import java.time.temporal.ChronoUnit

@DoNotDiscover
class CatalogueFileApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      RepositoryArbitraries {

  private val organizationMediaBucket            = "organization-media"
  private val catalogueItemImageBucketPathPrefix = "catalogue/item-images"

  override def beforeEach(): Unit = {
    super.beforeEach()

    withContext { context =>
      import context.*

      for {
        _ <- postgresClient.truncateTable(
          repositoryConfig.schema,
          repositoryConfig.userDetailsTable,
          repositoryConfig.organizationUserTable,
          repositoryConfig.catalogueItemTable,
          repositoryConfig.catalogueItemVariantTable,
        )
        _ <- s3TestClient.emptyAllBuckets()
      } yield ()
    }
  }

  "Catalogue File Service API" when {
    "/catalogue/file/upload/image/item" should {
      "successfully upload a catalogue item's image, storing the original and normalized objects and saving the image on the item" in withContext {
        context =>
          import context.*

          for {
            catalogueItemRow        = arbitrarySample[CatalogueItemRow].copy(isArchived = false)
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = catalogueItemRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
            )

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = catalogueItemRow.organizationID,
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
            catalogueItemImageBytes <- ZStream
              .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
              .runCollect
            catalogueItemImageBucketKeyPrefix =
              s"$catalogueItemImageBucketPathPrefix/${catalogueItemRow.organizationID.value}/${catalogueItemRow.catalogueItemID.value}"
            catalogueItemImageAssetExpected = CatalogueItemImageAsset.assume(
              ImageAsset(
                imageOriginalS3BucketKey =
                  ImageOriginalS3BucketKey.assume(s"$catalogueItemImageBucketKeyPrefix/original"),
                imageNormalizedS3BucketKey =
                  ImageNormalizedS3BucketKey.assume(s"$catalogueItemImageBucketKeyPrefix/normalized"),
                imageOriginalFileName = catalogueItemImageOriginalFileName,
              )
            )
            instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

            catalogueFileUploadImageItemPostResponse <- gatewayClient
              .catalogueFileUploadImageItemPost[smithy.InternalServerError](
                Some(catalogueItemRow.organizationID),
                Some(catalogueItemRow.catalogueItemID),
                Some(catalogueItemImageOriginalFileName),
                catalogueItemImageBytes,
                Some(accessJwt.accessToken),
              )

            _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.Ok
            _ = catalogueFileUploadImageItemPostResponse.body shouldBe Right(())

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll should contain theSameElementsAs List(
              s"$catalogueItemImageBucketKeyPrefix/original",
              s"$catalogueItemImageBucketKeyPrefix/normalized",
            )

            catalogueItemImageOriginalObjectBytes <- s3TestClient.getObject(
              organizationMediaBucket,
              s"$catalogueItemImageBucketKeyPrefix/original",
            )
            catalogueItemImageNormalizedObjectBytes <- s3TestClient.getObject(
              organizationMediaBucket,
              s"$catalogueItemImageBucketKeyPrefix/normalized",
            )

            _ = catalogueItemImageOriginalObjectBytes shouldBe catalogueItemImageBytes

            _ = catalogueItemImageNormalizedObjectBytes.size should be > 0

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll should have size 1
            _ = catalogueItemRowsAll shouldBe List(
              catalogueItemRow.copy(
                imageAsset = Some(catalogueItemImageAssetExpected),
                updatedAt = catalogueItemRowsAll.head.updatedAt,
              )
            )
            _ = catalogueItemRowsAll.head.imageAsset shouldNot equal(catalogueItemRow.imageAsset)
            _ = catalogueItemRowsAll.head.updatedAt.value.isBefore(instantNow) shouldBe false
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
          } yield ()
      }

      "successfully replace a catalogue item's existing image with the new upload" in withContext { context =>
        import context.*

        for {
          catalogueItemImageOriginalFileNamePrevious = ImageOriginalFileName.assume("catalogue-image-previous.jpeg")
          catalogueItemImageOriginalFileName         = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          organizationID                             = arbitrarySample[OrganizationID]
          catalogueItemID                            = arbitrarySample[CatalogueItemID]
          catalogueItemImageBucketKeyPrefix          =
            s"$catalogueItemImageBucketPathPrefix/${organizationID.value}/${catalogueItemID.value}"
          catalogueItemImageAssetPrevious = CatalogueItemImageAsset.assume(
            ImageAsset(
              imageOriginalS3BucketKey =
                ImageOriginalS3BucketKey.assume(s"$catalogueItemImageBucketKeyPrefix/original"),
              imageNormalizedS3BucketKey =
                ImageNormalizedS3BucketKey.assume(s"$catalogueItemImageBucketKeyPrefix/normalized"),
              imageOriginalFileName = catalogueItemImageOriginalFileNamePrevious,
            )
          )
          catalogueItemImageAssetExpected = CatalogueItemImageAsset.assume(
            ImageAsset(
              imageOriginalS3BucketKey =
                ImageOriginalS3BucketKey.assume(s"$catalogueItemImageBucketKeyPrefix/original"),
              imageNormalizedS3BucketKey =
                ImageNormalizedS3BucketKey.assume(s"$catalogueItemImageBucketKeyPrefix/normalized"),
              imageOriginalFileName = catalogueItemImageOriginalFileName,
            )
          )
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            imageAsset = Some(catalogueItemImageAssetPrevious),
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ = catalogueItemImageAssetExpected shouldNot equal(catalogueItemImageAssetPrevious)

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
              Some(catalogueItemRow.organizationID),
              Some(catalogueItemRow.catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              catalogueItemImageBytes,
              Some(accessJwt.accessToken),
            )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.Ok
          _ = catalogueFileUploadImageItemPostResponse.body shouldBe Right(())

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll should contain theSameElementsAs List(
            s"$catalogueItemImageBucketKeyPrefix/original",
            s"$catalogueItemImageBucketKeyPrefix/normalized",
          )

          catalogueItemImageOriginalObjectBytes <- s3TestClient.getObject(
            organizationMediaBucket,
            s"$catalogueItemImageBucketKeyPrefix/original",
          )

          _ = catalogueItemImageOriginalObjectBytes shouldBe catalogueItemImageBytes

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll should have size 1
          _ = catalogueItemRowsAll shouldBe List(
            catalogueItemRow.copy(
              imageAsset = Some(catalogueItemImageAssetExpected),
              updatedAt = catalogueItemRowsAll.head.updatedAt,
            )
          )
          _ = catalogueItemRowsAll.head.imageAsset shouldNot equal(catalogueItemRow.imageAsset)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)
        } yield ()
      }

      "fail with a BadRequest when the catalogue item ID header is missing" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            Some(catalogueItemImageOriginalFileName),
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name header is missing" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationUserRow.organizationID,
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )
          catalogueItemImageBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueItemRow.catalogueItemID),
            None,
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name is blank" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("   ")
          catalogueItemImageBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(catalogueItemRow.organizationID),
            Some(catalogueItemRow.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name is longer than allowed" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileNameOverMaxLength = 256
          catalogueItemImageOriginalFileName              = ImageOriginalFileName.assume(
            "a" * (catalogueItemImageOriginalFileNameOverMaxLength - ".jpeg".length) + ".jpeg"
          )
          catalogueItemImageBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(catalogueItemRow.organizationID),
            Some(catalogueItemRow.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name has no extension" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image")
          catalogueItemImageBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(catalogueItemRow.organizationID),
            Some(catalogueItemRow.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file extension is unsupported" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image.pdf")
          catalogueItemImageBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(catalogueItemRow.organizationID),
            Some(catalogueItemRow.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the filename and content disagree" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-malformed-it-1.png")
          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
            Some(catalogueItemRow.organizationID),
            Some(catalogueItemRow.catalogueItemID),
            Some(catalogueItemImageOriginalFileName),
            catalogueItemImageBytes,
            Some(accessJwt.accessToken),
          )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item is missing" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemID                    = arbitrarySample[CatalogueItemID]
          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              catalogueItemImageBytes,
              Some(accessJwt.accessToken),
            )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item is archived" in withContext { context =>
        import context.*

        for {
          catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = true,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRowArchived.organizationID,
            catalogueItemID = catalogueItemRowArchived.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowArchived))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRowArchived.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
              Some(catalogueItemRowArchived.organizationID),
              Some(catalogueItemRowArchived.catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              catalogueItemImageBytes,
              Some(accessJwt.accessToken),
            )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRowArchived)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item has no non-archived variant" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = true,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
              Some(catalogueItemRow.organizationID),
              Some(catalogueItemRow.catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              catalogueItemImageBytes,
              Some(accessJwt.accessToken),
            )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the catalogue item belongs to a different organization" in withContext {
        context =>
          import context.*

          for {
            catalogueItemRowForeign = arbitrarySample[CatalogueItemRow].copy(
              imageAsset = None,
              isArchived = false,
            )
            catalogueItemVariantRowForeign = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = catalogueItemRowForeign.organizationID,
              catalogueItemID = catalogueItemRowForeign.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRowForeign))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRowForeign))
            )

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
            catalogueItemImageBytes <- ZStream
              .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
              .runCollect

            catalogueFileUploadImageItemPostResponse <- gatewayClient
              .catalogueFileUploadImageItemPost[smithy.InternalServerError](
                Some(organizationUserRow.organizationID),
                Some(catalogueItemRowForeign.catalogueItemID),
                Some(catalogueItemImageOriginalFileName),
                catalogueItemImageBytes,
                Some(accessJwt.accessToken),
              )

            _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRowForeign)
            _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRowForeign)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the image cannot be processed" in withContext { context =>
        import context.*

        for {
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = catalogueItemRow.organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(List(catalogueItemVariantRow))
          )

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = catalogueItemRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-malformed-it-2.png")
          catalogueItemImageBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          catalogueFileUploadImageItemPostResponse <- gatewayClient
            .catalogueFileUploadImageItemPost[smithy.InternalServerError](
              Some(catalogueItemRow.organizationID),
              Some(catalogueItemRow.catalogueItemID),
              Some(catalogueItemImageOriginalFileName),
              catalogueItemImageBytes,
              Some(accessJwt.accessToken),
            )

          _ = catalogueFileUploadImageItemPostResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueFileUploadImageItemPostResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll shouldBe List(catalogueItemVariantRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }
    }

    "/catalogue/file/ai/extract" should {
      "successfully extract item candidates from an image without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect
          catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
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

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = catalogueFileAiExtractPostResponse.body.value shouldBe catalogueFileAiExtractPostResponseExpected

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract item candidates from CSV without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-1.csv")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect
          catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
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

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = catalogueFileAiExtractPostResponse.body.value shouldBe catalogueFileAiExtractPostResponseExpected

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract item candidates from XLSX without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-2.xlsx")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect
          catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
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

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = catalogueFileAiExtractPostResponse.body.value shouldBe catalogueFileAiExtractPostResponseExpected

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully extract item candidates from XLS without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-3.xls")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect
          catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
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

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
          _ = catalogueFileAiExtractPostResponse.body.value shouldBe catalogueFileAiExtractPostResponseExpected

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "successfully accept processing guidance at the length limit after trimming without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
            catalogueFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${catalogueFileExtractFileName.value}")
              .runCollect
            processingMessageMaxLength                 = 2000
            catalogueFileProcessingMessage             = s"  ${"a" * processingMessageMaxLength}  "
            catalogueFileAiExtractPostResponseExpected = CatalogueFileAiExtractPostResponse(
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

            catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some(catalogueFileProcessingMessage),
            )

            _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.Ok
            _ = catalogueFileAiExtractPostResponse.body.value shouldBe catalogueFileAiExtractPostResponseExpected

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe empty
            _ = catalogueItemVariantRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a BadRequest when the file name header is missing without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name is blank without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("   ")
          catalogueFileExtractFileBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name is longer than allowed without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueFileExtractFileNameOverMaxLength = 256
            catalogueFileExtractFileName              = CatalogueFileExtractFileName.assume(
              "a" * (catalogueFileExtractFileNameOverMaxLength - ".jpeg".length) + ".jpeg"
            )
            catalogueFileExtractFileBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

            catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

            _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
            _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe empty
            _ = catalogueItemVariantRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a BadRequest when processing guidance is too long without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect
          processingMessageOverMaxLength = 2001
          catalogueFileProcessingMessage = "a" * processingMessageOverMaxLength

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(catalogueFileProcessingMessage),
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file name has no extension without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image")
          catalogueFileExtractFileBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the file extension is unsupported without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image.pdf")
          catalogueFileExtractFileBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a BadRequest when the filename and content disagree without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image.png")
          catalogueFileExtractFileBytes <- ZStream.fromResource("assets/catalogue-image-it-1.jpeg").runCollect

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            None,
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.BadRequest
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.BadRequest()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the Excel upload is not a workbook without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue.xlsx")
            catalogueFileExtractFileBytes <- ZStream.fromResource("assets/catalogue-compressed-it-1.zip").runCollect

            catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              None,
            )

            _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe empty
            _ = catalogueItemVariantRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when image AI extraction fails without storing data" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileBytes <- ZStream
            .fromResource(s"assets/${catalogueFileExtractFileName.value}")
            .runCollect
          catalogueFileProcessingMessage = "AI_CLIENT_SPEC_ERROR"

          catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
            Some(organizationUserRow.organizationID),
            Some(catalogueFileExtractFileName),
            catalogueFileExtractFileBytes,
            Some(accessJwt.accessToken),
            Some(catalogueFileProcessingMessage),
          )

          _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
          _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe empty
          _ = catalogueItemVariantRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an InternalServerError when the AI result is malformed without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
            catalogueFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${catalogueFileExtractFileName.value}")
              .runCollect
            catalogueFileProcessingMessage = "AI_CLIENT_SPEC_MALFORMED"

            catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some(catalogueFileProcessingMessage),
            )

            _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe empty
            _ = catalogueItemVariantRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when spreadsheet AI extraction fails without storing data" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)
            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            catalogueFileExtractFileName = CatalogueFileExtractFileName.assume("catalogue-spreadsheet-it-1.csv")
            catalogueFileExtractFileBytes <- ZStream
              .fromResource(s"assets/${catalogueFileExtractFileName.value}")
              .runCollect
            catalogueFileProcessingMessage = "AI_CLIENT_SPEC_ERROR"

            catalogueFileAiExtractPostResponse <- gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
              Some(organizationUserRow.organizationID),
              Some(catalogueFileExtractFileName),
              catalogueFileExtractFileBytes,
              Some(accessJwt.accessToken),
              Some(catalogueFileProcessingMessage),
            )

            _ = catalogueFileAiExtractPostResponse.code shouldBe StatusCode.InternalServerError
            _ = catalogueFileAiExtractPostResponse.body.left.value shouldBe smithy.InternalServerError()

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe empty
            _ = catalogueItemVariantRowsAll shouldBe empty

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }
    }

    "middleware" should {
      "fail with a BadRequest when the organization id header is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow]
            .copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationID   = arbitrarySample[OrganizationID]
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueImageFileBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.catalogueFileUploadImageItemPost[smithy.BadRequest](
                None,
                Some(catalogueItemRow.catalogueItemID),
                Some(catalogueItemImageOriginalFileName),
                catalogueImageFileBytes,
                Some(accessJwt.accessToken),
              ),
              gatewayClient.catalogueFileAiExtractPost[smithy.BadRequest](
                None,
                Some(catalogueFileExtractFileName),
                catalogueImageFileBytes,
                Some(accessJwt.accessToken),
                None,
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.BadRequest)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.BadRequest()))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID   = arbitrarySample[OrganizationID]
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueImageFileBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.catalogueFileUploadImageItemPost[smithy.Unauthorized](
                Some(organizationID),
                Some(catalogueItemRow.catalogueItemID),
                Some(catalogueItemImageOriginalFileName),
                catalogueImageFileBytes,
                None,
              ),
              gatewayClient.catalogueFileAiExtractPost[smithy.Unauthorized](
                Some(organizationID),
                Some(catalogueFileExtractFileName),
                catalogueImageFileBytes,
                None,
                None,
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.Unauthorized()))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationID   = arbitrarySample[OrganizationID]
          catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
            organizationID = organizationID,
            imageAsset = None,
            isArchived = false,
          )
          catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )
          catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
            organizationID = organizationID,
            catalogueItemID = catalogueItemRow.catalogueItemID,
            isArchived = false,
          )

          _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
          _ <- postgresClient.executeQuery(
            catalogueQueries.insertCatalogueItemVariantRows(
              List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
            )
          )

          catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
          catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
          catalogueImageFileBytes <- ZStream
            .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
            .runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.catalogueFileUploadImageItemPost[smithy.Unauthorized](
                Some(organizationID),
                Some(catalogueItemRow.catalogueItemID),
                Some(catalogueItemImageOriginalFileName),
                catalogueImageFileBytes,
                Some(AccessToken("invalidtoken")),
              ),
              gatewayClient.catalogueFileAiExtractPost[smithy.Unauthorized](
                Some(organizationID),
                Some(catalogueFileExtractFileName),
                catalogueImageFileBytes,
                Some(AccessToken("invalidtoken")),
                None,
              ),
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.Unauthorized()))

          catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
          catalogueItemVariantRowsAll <- postgresClient.executeQuery(
            catalogueQueries.getAllCatalogueItemVariantRowsTesting
          )

          _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
          _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
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
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              imageAsset = None,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
            catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
            catalogueImageFileBytes <- ZStream
              .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
              .runCollect

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueFileUploadImageItemPost[smithy.Forbidden](
                  Some(organizationUserRow.organizationID),
                  Some(catalogueItemRow.catalogueItemID),
                  Some(catalogueItemImageOriginalFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueFileAiExtractPost[smithy.Forbidden](
                  Some(organizationUserRow.organizationID),
                  Some(catalogueFileExtractFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.Forbidden()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a Forbidden when the organization user role is not allowed on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)
            organizationUserRoleInvalid <- Random
              .shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles)
              .map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userDetailsRow.userID, userRole = organizationUserRoleInvalid)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))
            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              imageAsset = None,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
            catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
            catalogueImageFileBytes <- ZStream
              .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
              .runCollect

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueFileUploadImageItemPost[smithy.Forbidden](
                  Some(organizationUserRow.organizationID),
                  Some(catalogueItemRow.catalogueItemID),
                  Some(catalogueItemImageOriginalFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueFileAiExtractPost[smithy.Forbidden](
                  Some(organizationUserRow.organizationID),
                  Some(catalogueFileExtractFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.Forbidden()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            userID = arbitrarySample[UserID]
            organizationUserRole <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow]
              .copy(userID = userID, userRole = organizationUserRole)

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationUserRow.organizationID,
              imageAsset = None,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationUserRow.organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
            catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
            catalogueImageFileBytes <- ZStream
              .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
              .runCollect

            accessJwt <- jwtService.generateAccessToken(userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueFileUploadImageItemPost[smithy.InternalServerError](
                  Some(organizationUserRow.organizationID),
                  Some(catalogueItemRow.catalogueItemID),
                  Some(catalogueItemImageOriginalFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
                  Some(organizationUserRow.organizationID),
                  Some(catalogueFileExtractFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.InternalServerError()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user is not a member of the organization on every endpoint" in withContext {
        context =>
          import context.*

          for {
            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow]
              .copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationID   = arbitrarySample[OrganizationID]
            catalogueItemRow = arbitrarySample[CatalogueItemRow].copy(
              organizationID = organizationID,
              imageAsset = None,
              isArchived = false,
            )
            catalogueItemVariantRow = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )
            catalogueItemVariantRowSibling = arbitrarySample[CatalogueItemVariantRow].copy(
              organizationID = organizationID,
              catalogueItemID = catalogueItemRow.catalogueItemID,
              isArchived = false,
            )

            _ <- postgresClient.executeQuery(catalogueQueries.insertCatalogueItemRow(catalogueItemRow))
            _ <- postgresClient.executeQuery(
              catalogueQueries.insertCatalogueItemVariantRows(
                List(catalogueItemVariantRow, catalogueItemVariantRowSibling)
              )
            )

            catalogueItemImageOriginalFileName = ImageOriginalFileName.assume("catalogue-image-it-1.jpeg")
            catalogueFileExtractFileName       = CatalogueFileExtractFileName.assume("catalogue-image-it-1.jpeg")
            catalogueImageFileBytes <- ZStream
              .fromResource(s"assets/${catalogueItemImageOriginalFileName.value}")
              .runCollect

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.catalogueFileUploadImageItemPost[smithy.InternalServerError](
                  Some(organizationID),
                  Some(catalogueItemRow.catalogueItemID),
                  Some(catalogueItemImageOriginalFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                ),
                gatewayClient.catalogueFileAiExtractPost[smithy.InternalServerError](
                  Some(organizationID),
                  Some(catalogueFileExtractFileName),
                  catalogueImageFileBytes,
                  Some(accessJwt.accessToken),
                  None,
                ),
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(2)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(2)(Some(smithy.InternalServerError()))

            catalogueItemRowsAll        <- postgresClient.executeQuery(catalogueQueries.getAllCatalogueItemRowsTesting)
            catalogueItemVariantRowsAll <- postgresClient.executeQuery(
              catalogueQueries.getAllCatalogueItemVariantRowsTesting
            )

            _ = catalogueItemRowsAll shouldBe List(catalogueItemRow)
            _ = catalogueItemVariantRowsAll.toSet shouldBe Set(catalogueItemVariantRow, catalogueItemVariantRowSibling)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }
    }
  }
}
