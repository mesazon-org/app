package io.mesazon.gateway.it

import com.dimafeng.testcontainers.DockerComposeContainer
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
class FileApiSpec(dockerComposeContainer: => DockerComposeContainer)
    extends GatewayAcceptanceTest(dockerComposeContainer),
      SmithyArbitraries,
      RepositoryArbitraries,
      IronRefinedTypeTransformer {

  // Mirrors the gateway container's s3-client-organization-media config (application.conf)
  private val organizationMediaBucket          = "organization-media"
  private val organizationLogoBucketPathPrefix = "organization/logos"

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
        _ <- s3TestClient.emptyAllBuckets()
      } yield ()
    }
  }

  "File Service API" when {
    "/upload/organization/logo" should {
      "successfully upload the logo, store the original and normalized objects in S3, save the logo asset and move the organization to LogoProvided" in withContext {
        context =>
          import context.*

          for {
            organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
              .copy(
                logoImageAsset = None
              )

            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationDetailsRow.organizationID,
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
            organizationID                        = organizationDetailsRow.organizationID
            logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

            uploadOrganizationLogoResponse <- gatewayClient.uploadOrganizationLogoPost[smithy.InternalServerError](
              Some(organizationID),
              Some(organizationLogoImageOriginalFileName),
              logoBytes,
              Some(accessJwt.accessToken),
            )

            _ = uploadOrganizationLogoResponse.code shouldBe StatusCode.Ok
            _ = assert(uploadOrganizationLogoResponse.body.isRight)

            expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

            logoOriginalObjectBytes <- s3TestClient.getObject(
              organizationMediaBucket,
              s"$expectedBucketKeyPrefix/original",
            )
            logoNormalizedObjectBytes <- s3TestClient.getObject(
              organizationMediaBucket,
              s"$expectedBucketKeyPrefix/normalized",
            )

            _ = logoOriginalObjectBytes shouldBe logoBytes

            _ = logoNormalizedObjectBytes.size should be > 0

            organizationDetailsRowUpdated <- postgresClient
              .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
              .map(_.head)

            _ = organizationDetailsRowUpdated.organizationStage shouldBe OrganizationStage.LogoProvided

            logoImageAssetValue = organizationDetailsRowUpdated.logoImageAsset.value.value

            _ = logoImageAssetValue.imageOriginalFileName shouldBe organizationLogoImageOriginalFileName
            _ = logoImageAssetValue.imageOriginalS3BucketKey.value shouldBe s"$expectedBucketKeyPrefix/original"
            _ = logoImageAssetValue.imageNormalizedS3BucketKey.value shouldBe s"$expectedBucketKeyPrefix/normalized"
          } yield ()
      }

      "successfully overwrite the saved logo on a repeat logo upload" in withContext { context =>
        import context.*

        for {
          organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
            .copy(
              logoImageAsset = None
            )

          _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = organizationDetailsRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          organizationLogoImageOriginalFileNameFirst  = ImageOriginalFileName.assume("organization-logo-first.jpeg")
          organizationLogoImageOriginalFileNameSecond = ImageOriginalFileName.assume("organization-logo-second.jpeg")
          organizationID                              = organizationDetailsRow.organizationID
          logoBytes <- ZStream.fromResource("assets/organization-image-it-1.jpeg").runCollect

          uploadOrganizationLogoResponseFirst <- gatewayClient.uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileNameFirst),
            logoBytes,
            Some(accessJwt.accessToken),
          )

          _ = uploadOrganizationLogoResponseFirst.code shouldBe StatusCode.Ok

          organizationDetailsRowAfterFirstUpload <- postgresClient
            .executeQuery(organizationDetailsQueries.getAllOrganizationDetailsTesting)
            .map(_.head)

          uploadOrganizationLogoResponseSecond <- gatewayClient.uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileNameSecond),
            logoBytes,
            Some(accessJwt.accessToken),
          )

          _ = uploadOrganizationLogoResponseSecond.code shouldBe StatusCode.Ok

          expectedBucketKeyPrefix          = s"$organizationLogoBucketPathPrefix/${organizationID.value}"
          organizationLogoImageAssetSecond = OrganizationLogoImageAsset.assume(
            ImageAsset(
              imageOriginalS3BucketKey = ImageOriginalS3BucketKey.assume(s"$expectedBucketKeyPrefix/original"),
              imageNormalizedS3BucketKey = ImageNormalizedS3BucketKey.assume(s"$expectedBucketKeyPrefix/normalized"),
              imageOriginalFileName = organizationLogoImageOriginalFileNameSecond,
            )
          )

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll should have size 1
          _ = organizationDetailsRowsAll.head.organizationStage shouldBe OrganizationStage.LogoProvided
          _ = organizationDetailsRowsAll.head.logoImageAsset shouldBe Some(organizationLogoImageAssetSecond)
          _ = organizationDetailsRowsAll.head.logoImageAsset shouldNot equal(
            organizationDetailsRowAfterFirstUpload.logoImageAsset
          )

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll should contain theSameElementsAs List(
            s"$expectedBucketKeyPrefix/original",
            s"$expectedBucketKeyPrefix/normalized",
          )

          logoOriginalObjectBytes <- s3TestClient.getObject(
            organizationMediaBucket,
            s"$expectedBucketKeyPrefix/original",
          )

          _ = logoOriginalObjectBytes shouldBe logoBytes
        } yield ()
      }

      "fail with BadRequest when the file name header is missing" in withContext { context =>
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

          logoBytes <- ZStream.fromResource("assets/organization-image-it-1.jpeg").runCollect

          uploadOrganizationLogoResponse <- gatewayClient.uploadOrganizationLogoPost[smithy.BadRequest](
            Some(organizationUserRow.organizationID),
            None,
            logoBytes,
            Some(accessJwt.accessToken),
          )

          _ = uploadOrganizationLogoResponse.code shouldBe StatusCode.BadRequest
          _ = uploadOrganizationLogoResponse.body.left.value shouldBe smithy.BadRequest()

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll shouldBe empty

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with InternalServerError when the uploaded file is not a supported image" in withContext { context =>
        import context.*

        for {
          organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
            .copy(
              logoImageAsset = None
            )

          _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
          organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
            organizationID = organizationDetailsRow.organizationID,
            userID = userDetailsRow.userID,
            userRole = organizationUserRoleAllowed,
          )

          _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-malformed-it-1.png")
          organizationID                        = organizationDetailsRow.organizationID
          logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

          uploadOrganizationLogoResponse <- gatewayClient.uploadOrganizationLogoPost[smithy.InternalServerError](
            Some(organizationID),
            Some(organizationLogoImageOriginalFileName),
            logoBytes,
            Some(accessJwt.accessToken),
          )

          _ = uploadOrganizationLogoResponse.code shouldBe StatusCode.InternalServerError
          _ = uploadOrganizationLogoResponse.body.left.value shouldBe smithy.InternalServerError()

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

          expectedBucketKeyPrefix = s"$organizationLogoBucketPathPrefix/${organizationID.value}"

          logoOriginalObjectBytesEither <- s3TestClient
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/original")
            .either
          logoNormalizedObjectBytesEither <- s3TestClient
            .getObject(organizationMediaBucket, s"$expectedBucketKeyPrefix/normalized")
            .either

          _ = logoOriginalObjectBytesEither.isLeft shouldBe true

          _ = logoNormalizedObjectBytesEither.isLeft shouldBe true
        } yield ()
      }
    }

    "middleware" should {
      "fail with a BadRequest when the organization id header is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
            .copy(logoImageAsset = None)

          _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

          onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
          userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

          _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

          accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

          organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
          logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.uploadOrganizationLogoPost[smithy.BadRequest](
                None,
                Some(organizationLogoImageOriginalFileName),
                logoBytes,
                Some(accessJwt.accessToken),
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.BadRequest)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.BadRequest()))

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is missing on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
            .copy(logoImageAsset = None)

          _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

          organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
          logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.uploadOrganizationLogoPost[smithy.Unauthorized](
                Some(organizationDetailsRow.organizationID),
                Some(organizationLogoImageOriginalFileName),
                logoBytes,
                None,
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Unauthorized()))

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with an Unauthorized when the access token is invalid on every endpoint" in withContext { context =>
        import context.*

        for {
          organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
            .copy(logoImageAsset = None)

          _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

          organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
          logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

          responses <- ZIO.collectAllPar(
            List(
              gatewayClient.uploadOrganizationLogoPost[smithy.Unauthorized](
                Some(organizationDetailsRow.organizationID),
                Some(organizationLogoImageOriginalFileName),
                logoBytes,
                Some(AccessToken("invalidtoken")),
              )
            )
          )

          _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Unauthorized)
          _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Unauthorized()))

          organizationDetailsRowsAll <- postgresClient.executeQuery(
            organizationDetailsQueries.getAllOrganizationDetailsTesting
          )

          _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

          organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

          _ = organizationMediaS3BucketKeysAll shouldBe empty
        } yield ()
      }

      "fail with a Forbidden when the user is not in a completed onboard stage on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
              .copy(logoImageAsset = None)

            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

            onboardStageInvalid <- Random
              .shuffle(OnboardStage.values.toList diff OnboardStage.completedStages)
              .map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStageInvalid)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationDetailsRow.organizationID,
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
            logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.uploadOrganizationLogoPost[smithy.Forbidden](
                  Some(organizationDetailsRow.organizationID),
                  Some(organizationLogoImageOriginalFileName),
                  logoBytes,
                  Some(accessJwt.accessToken),
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Forbidden()))

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )

            _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with a Forbidden when the organization user role is not allowed on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
              .copy(logoImageAsset = None)

            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            organizationUserRoleInvalid <- Random
              .shuffle(OrganizationUserRole.values.toList diff OrganizationUserRole.adminRoles)
              .map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationDetailsRow.organizationID,
              userID = userDetailsRow.userID,
              userRole = organizationUserRoleInvalid,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
            logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.uploadOrganizationLogoPost[smithy.Forbidden](
                  Some(organizationDetailsRow.organizationID),
                  Some(organizationLogoImageOriginalFileName),
                  logoBytes,
                  Some(accessJwt.accessToken),
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.Forbidden)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.Forbidden()))

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )

            _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user details do not exist on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
              .copy(logoImageAsset = None)

            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

            userID = arbitrarySample[UserID]

            organizationUserRoleAllowed <- Random.shuffle(OrganizationUserRole.adminRoles).map(_.head)
            organizationUserRow = arbitrarySample[OrganizationUserRow].copy(
              organizationID = organizationDetailsRow.organizationID,
              userID = userID,
              userRole = organizationUserRoleAllowed,
            )

            _ <- postgresClient.executeQuery(organizationUserQueries.insert(organizationUserRow))

            accessJwt <- jwtService.generateAccessToken(userID)

            organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
            logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.uploadOrganizationLogoPost[smithy.InternalServerError](
                  Some(organizationDetailsRow.organizationID),
                  Some(organizationLogoImageOriginalFileName),
                  logoBytes,
                  Some(accessJwt.accessToken),
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.InternalServerError()))

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )

            _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }

      "fail with an InternalServerError when the user is not a member of the organization on every endpoint" in withContext {
        context =>
          import context.*

          for {
            organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
              .copy(logoImageAsset = None)

            _ <- postgresClient.executeQuery(organizationDetailsQueries.insert(organizationDetailsRow))

            onboardStage <- Random.shuffle(OnboardStage.completedStages).map(_.head)
            userDetailsRow = arbitrarySample[UserDetailsRow].copy(onboardStage = onboardStage)

            _ <- postgresClient.executeQuery(userDetailsQueries.insertUserDetails(userDetailsRow))

            accessJwt <- jwtService.generateAccessToken(userDetailsRow.userID)

            organizationLogoImageOriginalFileName = ImageOriginalFileName.assume("organization-image-it-1.jpeg")
            logoBytes <- ZStream.fromResource(s"assets/${organizationLogoImageOriginalFileName.value}").runCollect

            responses <- ZIO.collectAllPar(
              List(
                gatewayClient.uploadOrganizationLogoPost[smithy.InternalServerError](
                  Some(organizationDetailsRow.organizationID),
                  Some(organizationLogoImageOriginalFileName),
                  logoBytes,
                  Some(accessJwt.accessToken),
                )
              )
            )

            _ = responses.map(_.code) shouldBe List.fill(1)(StatusCode.InternalServerError)
            _ = responses.map(_.body.left.toOption) shouldBe List.fill(1)(Some(smithy.InternalServerError()))

            organizationDetailsRowsAll <- postgresClient.executeQuery(
              organizationDetailsQueries.getAllOrganizationDetailsTesting
            )

            _ = organizationDetailsRowsAll shouldBe List(organizationDetailsRow)

            organizationMediaS3BucketKeysAll <- s3TestClient.listObjectKeys(organizationMediaBucket)

            _ = organizationMediaS3BucketKeysAll shouldBe empty
          } yield ()
      }
    }
  }
}
