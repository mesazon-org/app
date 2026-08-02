package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.{OrganizationLogosS3Client, S3ClientOrganizationMedia}
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

class FileServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

  "FileService" when {
    "uploadOrganizationLogo" should {
      "successfully scan, normalize, upload and persist the organization logo" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val organizationLogoOriginalFileName = arbitrarySample[OrganizationLogoOriginalFileName]
        val organizationLogoByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val organizationLogoOriginalBucketKey   = arbitrarySample[OrganizationLogoOriginalBucketKey]
        val organizationLogoNormalizedBucketKey = arbitrarySample[OrganizationLogoNormalizedBucketKey]
        val uploadedLogoResult: OrganizationLogosS3Client.UploadedLogoResult =
          (
            organizationLogoOriginalBucketKey = organizationLogoOriginalBucketKey,
            organizationLogoNormalizedBucketKey = organizationLogoNormalizedBucketKey,
          )

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          organizationS3ClientMock.upload
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedLogoResult)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationID,
              Some(OrganizationStage.LogoProvided),
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              Some(organizationLogoOriginalBucketKey),
              Some(organizationLogoNormalizedBucketKey),
              Some(organizationLogoOriginalFileName),
            )
            .returningZIO(arbitrarySample[OrganizationDetailsRow])
            .once(),
        )

        val fileService = buildFileService

        val response = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoOriginalFileName = organizationLogoOriginalFileName,
            organizationLogoFile = organizationLogoByteStream,
          )
          .zioEither

        response shouldBe Right(())
      }

      "fail and stop the pipeline when scanning the organization logo fails" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val organizationLogoOriginalFileName = arbitrarySample[OrganizationLogoOriginalFileName]
        val organizationLogoByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scanError = ServiceError.InternalServerError.UnexpectedError("Failed to scan organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.fail(scanError))
            .once()
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoOriginalFileName = organizationLogoOriginalFileName,
            organizationLogoFile = organizationLogoByteStream,
          )
          .zioError

        serviceError shouldBe scanError
      }

      "fail and stop the pipeline when normalizing the organization logo fails" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val organizationLogoOriginalFileName = arbitrarySample[OrganizationLogoOriginalFileName]
        val organizationLogoByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scannedByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizeError = ServiceError.InternalServerError.UnexpectedError("Failed to normalize organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.fail(normalizeError))
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoOriginalFileName = organizationLogoOriginalFileName,
            organizationLogoFile = organizationLogoByteStream,
          )
          .zioError

        serviceError shouldBe normalizeError
      }

      "fail and stop the pipeline when uploading the organization logo to S3 fails" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val organizationLogoOriginalFileName = arbitrarySample[OrganizationLogoOriginalFileName]
        val organizationLogoByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val uploadError = ServiceError.InternalServerError.UnexpectedError("Failed to upload organization logo to S3")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          organizationS3ClientMock.upload
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .failingZIO(uploadError)
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoOriginalFileName = organizationLogoOriginalFileName,
            organizationLogoFile = organizationLogoByteStream,
          )
          .zioError

        serviceError shouldBe uploadError
      }

      "fail when persisting the organization logo details fails" in new TestContext {
        val organizationID                   = arbitrarySample[OrganizationID]
        val organizationLogoOriginalFileName = arbitrarySample[OrganizationLogoOriginalFileName]
        val organizationLogoByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val organizationLogoOriginalBucketKey   = arbitrarySample[OrganizationLogoOriginalBucketKey]
        val organizationLogoNormalizedBucketKey = arbitrarySample[OrganizationLogoNormalizedBucketKey]
        val uploadedLogoResult: OrganizationLogosS3Client.UploadedLogoResult =
          (
            organizationLogoOriginalBucketKey = organizationLogoOriginalBucketKey,
            organizationLogoNormalizedBucketKey = organizationLogoNormalizedBucketKey,
          )

        val updateError = ServiceError.InternalServerError.UnexpectedError("Failed to persist organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          organizationS3ClientMock.upload
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedLogoResult)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationID,
              Some(OrganizationStage.LogoProvided),
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              Some(organizationLogoOriginalBucketKey),
              Some(organizationLogoNormalizedBucketKey),
              Some(organizationLogoOriginalFileName),
            )
            .failingZIO(updateError)
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoOriginalFileName = organizationLogoOriginalFileName,
            organizationLogoFile = organizationLogoByteStream,
          )
          .zioError

        serviceError shouldBe updateError
      }
    }

    "uploadCatalogueItemImage" should {
      "successfully scan, normalize, upload and persist the catalogue item image" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val catalogueItemImageOriginalBucketKey   = arbitrarySample[ImageOriginalBucketKey]
        val catalogueItemImageNormalizedBucketKey = arbitrarySample[ImageNormalizedBucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedCatalogueItemImageResult =
          (
            catalogueItemImageOriginalBucketKey = catalogueItemImageOriginalBucketKey,
            catalogueItemImageNormalizedBucketKey = catalogueItemImageNormalizedBucketKey,
          )

        val catalogueItemImage = CatalogueItemImage.assume(
          Image(
            originalBucketKey = catalogueItemImageOriginalBucketKey,
            normalizedBucketKey = catalogueItemImageNormalizedBucketKey,
            originalFileName = catalogueItemImageOriginalFileName,
          )
        )

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              SupportedMediaTypes.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          catalogueItemS3ClientMock.upload
            .expects(organizationID, catalogueItemID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedImageResult)
            .once(),
          catalogueRepositoryMock.updateCatalogueItem
            .expects(
              organizationID,
              catalogueItemID,
              None,
              None,
              None,
              Some(catalogueItemImage),
            )
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
        )

        val fileService = buildFileService

        val response = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioEither

        response shouldBe Right(())
      }

      "fail with InternalServerError when the catalogue item is missing" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(None)
            .once()
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }

      "fail with InternalServerError when the catalogue item is archived" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val catalogueItemRowArchived = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Archived,
        )

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowArchived))
            .once()
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }

      "fail with InternalServerError when the catalogue item is not found (foreign organization isolation)" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(None)
            .once()
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
      }

      "fail and stop the pipeline with InternalServerError when scanning the catalogue item image fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val scanError = ServiceError.InternalServerError.UnexpectedError("Failed to scan catalogue item image")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              SupportedMediaTypes.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(ZIO.fail(scanError))
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe scanError
      }

      "fail and stop the pipeline with InternalServerError when normalizing the catalogue item image fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val scannedByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizeError    =
          ServiceError.InternalServerError.UnexpectedError("Failed to normalize catalogue item image")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              SupportedMediaTypes.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.fail(normalizeError))
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe normalizeError
      }

      "fail and stop the pipeline with InternalServerError when uploading the catalogue item image to S3 fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val uploadError =
          ServiceError.InternalServerError.UnexpectedError("Failed to upload catalogue item image to S3")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              SupportedMediaTypes.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          catalogueItemS3ClientMock.upload
            .expects(organizationID, catalogueItemID, originalByteStream, normalizedByteStream)
            .failingZIO(uploadError)
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe uploadError
      }

      "fail with InternalServerError when persisting the catalogue item image details fails" in new TestContext {
        val organizationID                     = arbitrarySample[OrganizationID]
        val catalogueItemID                    = arbitrarySample[CatalogueItemID]
        val catalogueItemImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val catalogueItemImageOriginalBucketKey   = arbitrarySample[ImageOriginalBucketKey]
        val catalogueItemImageNormalizedBucketKey = arbitrarySample[ImageNormalizedBucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedCatalogueItemImageResult =
          (
            catalogueItemImageOriginalBucketKey = catalogueItemImageOriginalBucketKey,
            catalogueItemImageNormalizedBucketKey = catalogueItemImageNormalizedBucketKey,
          )

        val catalogueItemImage = CatalogueItemImage.assume(
          Image(
            originalBucketKey = catalogueItemImageOriginalBucketKey,
            normalizedBucketKey = catalogueItemImageNormalizedBucketKey,
            originalFileName = catalogueItemImageOriginalFileName,
          )
        )

        val updateError = ServiceError.InternalServerError.UnexpectedError("Failed to persist catalogue item image")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              SupportedMediaTypes.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          catalogueItemS3ClientMock.upload
            .expects(organizationID, catalogueItemID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedImageResult)
            .once(),
          catalogueRepositoryMock.updateCatalogueItem
            .expects(
              organizationID,
              catalogueItemID,
              None,
              None,
              None,
              Some(catalogueItemImage),
            )
            .failingZIO(updateError)
            .once(),
        )

        val fileService = buildFileService

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageFile = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe updateError
      }
    }
  }

  trait TestContext {
    val fileServiceConfig = FileServiceConfig(
      maxUploadBytes = 5L * 1024 * 1024
    )

    val fileScannerMock                      = mock[FileScanner]
    val imageProcessingMock                  = mock[ImageProcessing]
    val organizationS3ClientMock             = mock[OrganizationLogosS3Client]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]
    val catalogueRepositoryMock              = mock[CatalogueRepository]
    val catalogueItemS3ClientMock            = mock[S3ClientOrganizationMedia]

    def buildFileService: FileService[ServiceTask] = ZIO
      .service[FileService[ServiceTask]]
      .provide(
        FileService.local,
        ZLayer.succeed(fileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(organizationS3ClientMock),
        ZLayer.succeed(organizationManagementRepositoryMock),
        ZLayer.succeed(catalogueRepositoryMock),
        ZLayer.succeed(catalogueItemS3ClientMock),
      )
      .zioValue
  }
}
