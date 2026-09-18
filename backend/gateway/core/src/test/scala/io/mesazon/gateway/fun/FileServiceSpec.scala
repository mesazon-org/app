package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.mock.Mocks
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.Files

class FileServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

  "FileService" when {
    "uploadOrganizationLogo" should {
      "successfully scan, normalize, upload and persist the organization logo" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val organizationLogoImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val organizationLogoImageOriginalS3BucketKey   = arbitrarySample[ImageOriginalS3BucketKey]
        val organizationLogoImageNormalizedS3BucketKey = arbitrarySample[ImageNormalizedS3BucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedImageResult =
          (
            imageOriginalS3BucketKey = organizationLogoImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = organizationLogoImageNormalizedS3BucketKey,
          )

        val organizationLogoImageAsset = OrganizationLogoImageAsset.assume(
          ImageAsset(
            imageOriginalS3BucketKey = organizationLogoImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = organizationLogoImageNormalizedS3BucketKey,
            imageOriginalFileName = organizationLogoImageOriginalFileName,
          )
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              organizationLogoImageByteStream,
              organizationLogoImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          s3ClientOrganizationMediaMock.uploadImageOrganizationLogo
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedImageResult)
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
              Some(organizationLogoImageAsset),
            )
            .returningZIO(arbitrarySample[OrganizationDetailsRow])
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val response = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoImageOriginalFileName = organizationLogoImageOriginalFileName,
            organizationLogoImageByteStream = organizationLogoImageByteStream,
          )
          .zioEither

        response shouldBe Right(())
      }

      "fail and stop the pipeline when scanning the organization logo fails" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val organizationLogoImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scanError = ServiceError.InternalServerError.UnexpectedError("Failed to scan organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(
              organizationLogoImageByteStream,
              organizationLogoImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(ZIO.fail(scanError))
            .once()
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoImageOriginalFileName = organizationLogoImageOriginalFileName,
            organizationLogoImageByteStream = organizationLogoImageByteStream,
          )
          .zioError

        serviceError shouldBe scanError
      }

      "fail and stop the pipeline when normalizing the organization logo fails" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val organizationLogoImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val normalizeError = ServiceError.InternalServerError.UnexpectedError("Failed to normalize organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(
              organizationLogoImageByteStream,
              organizationLogoImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.fail(normalizeError))
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoImageOriginalFileName = organizationLogoImageOriginalFileName,
            organizationLogoImageByteStream = organizationLogoImageByteStream,
          )
          .zioError

        serviceError shouldBe normalizeError
      }

      "fail and stop the pipeline when uploading the organization logo to S3 fails" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val organizationLogoImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val uploadError = ServiceError.InternalServerError.UnexpectedError("Failed to upload organization logo to S3")

        inSequence(
          fileScannerMock.scan
            .expects(
              organizationLogoImageByteStream,
              organizationLogoImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          s3ClientOrganizationMediaMock.uploadImageOrganizationLogo
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .failingZIO(uploadError)
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoImageOriginalFileName = organizationLogoImageOriginalFileName,
            organizationLogoImageByteStream = organizationLogoImageByteStream,
          )
          .zioError

        serviceError shouldBe uploadError
      }

      "fail when persisting the organization logo details fails" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val organizationLogoImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/test-logo-1.jpeg")

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val organizationLogoImageOriginalS3BucketKey   = arbitrarySample[ImageOriginalS3BucketKey]
        val organizationLogoImageNormalizedS3BucketKey = arbitrarySample[ImageNormalizedS3BucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedImageResult =
          (
            imageOriginalS3BucketKey = organizationLogoImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = organizationLogoImageNormalizedS3BucketKey,
          )

        val organizationLogoImageAsset = OrganizationLogoImageAsset.assume(
          ImageAsset(
            imageOriginalS3BucketKey = organizationLogoImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = organizationLogoImageNormalizedS3BucketKey,
            imageOriginalFileName = organizationLogoImageOriginalFileName,
          )
        )

        val updateError = ServiceError.InternalServerError.UnexpectedError("Failed to persist organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(
              organizationLogoImageByteStream,
              organizationLogoImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          s3ClientOrganizationMediaMock.uploadImageOrganizationLogo
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedImageResult)
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
              Some(organizationLogoImageAsset),
            )
            .failingZIO(updateError)
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadOrganizationLogo(
            organizationID = organizationID,
            organizationLogoImageOriginalFileName = organizationLogoImageOriginalFileName,
            organizationLogoImageByteStream = organizationLogoImageByteStream,
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

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val catalogueItemImageOriginalS3BucketKey   = arbitrarySample[ImageOriginalS3BucketKey]
        val catalogueItemImageNormalizedS3BucketKey = arbitrarySample[ImageNormalizedS3BucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedImageResult =
          (
            imageOriginalS3BucketKey = catalogueItemImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = catalogueItemImageNormalizedS3BucketKey,
          )

        val catalogueItemImageAsset = CatalogueItemImageAsset.assume(
          ImageAsset(
            imageOriginalS3BucketKey = catalogueItemImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = catalogueItemImageNormalizedS3BucketKey,
            imageOriginalFileName = catalogueItemImageOriginalFileName,
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
              catalogueItemImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          s3ClientOrganizationMediaMock.uploadImageCatalogueItem
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
              Some(catalogueItemImageAsset),
            )
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val response = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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
              catalogueItemImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(ZIO.fail(scanError))
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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

        val normalizeError =
          ServiceError.InternalServerError.UnexpectedError("Failed to normalize catalogue item image")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(Some(catalogueItemRowActive))
            .once(),
          fileScannerMock.scan
            .expects(
              catalogueItemImageByteStream,
              catalogueItemImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.fail(normalizeError))
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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
              catalogueItemImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          s3ClientOrganizationMediaMock.uploadImageCatalogueItem
            .expects(organizationID, catalogueItemID, originalByteStream, normalizedByteStream)
            .failingZIO(uploadError)
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
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

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val catalogueItemImageOriginalS3BucketKey   = arbitrarySample[ImageOriginalS3BucketKey]
        val catalogueItemImageNormalizedS3BucketKey = arbitrarySample[ImageNormalizedS3BucketKey]
        val uploadedImageResult: S3ClientOrganizationMedia.UploadedImageResult =
          (
            imageOriginalS3BucketKey = catalogueItemImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = catalogueItemImageNormalizedS3BucketKey,
          )

        val catalogueItemImageAsset = CatalogueItemImageAsset.assume(
          ImageAsset(
            imageOriginalS3BucketKey = catalogueItemImageOriginalS3BucketKey,
            imageNormalizedS3BucketKey = catalogueItemImageNormalizedS3BucketKey,
            imageOriginalFileName = catalogueItemImageOriginalFileName,
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
              catalogueItemImageOriginalFileName.value,
              SupportedMediaType.images,
              fileServiceConfig.fileBytesMax,
            )
            .returns(
              ZIO.succeed(
                (
                  fileScannedPath = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg")),
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(*, SupportedMediaType.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          s3ClientOrganizationMediaMock.uploadImageCatalogueItem
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
              Some(catalogueItemImageAsset),
            )
            .failingZIO(updateError)
            .once(),
        )

        val fileService = buildFileService(aiClientMock)

        val serviceError = fileService
          .uploadCatalogueItemImage(
            organizationID = organizationID,
            catalogueItemID = catalogueItemID,
            catalogueItemImageOriginalFileName = catalogueItemImageOriginalFileName,
            catalogueItemImageByteStream = catalogueItemImageByteStream,
          )
          .zioError

        serviceError shouldBe updateError
      }
    }

    "extractCustomers" should {
      "return the AI's extracted candidates for an image" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val extractCustomersFileName       = ExtractCustomersFileName.assume("customers.jpeg")
        val extractCustomersFileByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")
        val fileScannedPath                = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg"))
        fileScannedPath.value.toFile.deleteOnExit()
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = fileScannedPath,
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val entriesIdentified            = 0L
        val entriesProcessed             = 0L
        val extractCustomersPostResponse = ExtractCustomersPostResponse(
          entriesIdentified = entriesIdentified,
          entriesProcessed = entriesProcessed,
          customerIndividualCandidates = List.empty,
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )

        fileScannerMock.scan
          .expects(
            extractCustomersFileByteStream,
            extractCustomersFileName.value,
            SupportedMediaType.extractData,
            fileServiceConfig.fileBytesMax,
          )
          .returns(ZIO.succeed(fileScannerScanOutput))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(CsvValidatedPath, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(extractCustomersPostResponse)),
        )
        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe extractCustomersPostResponse
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, FileService.extractCustomersFromImageInstructions)
        )
        extractFromCsvCallsRef.refValue shouldBe List.empty
      }

      "return the AI's extracted candidates for a spreadsheet after CSV conversion" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val extractCustomersFileName       = ExtractCustomersFileName.assume("customers.csv")
        val customerBookCsvBytes           = "Full Name,Email".getBytes(StandardCharsets.UTF_8)
        val extractCustomersFileByteStream = ZStream.fromIterable(customerBookCsvBytes)
        val fileScannedPath                = FileScannedPath(Files.createTempFile("file-service-spec-", ".csv"))
        fileScannedPath.value.toFile.deleteOnExit()
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = fileScannedPath,
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerBookCsvPath = Files.createTempFile("file-service-spec-validated-", ".csv")
        Files.write(customerBookCsvPath, customerBookCsvBytes)
        customerBookCsvPath.toFile.deleteOnExit()
        val csvValidatedPath             = CsvValidatedPath(customerBookCsvPath)
        val entriesIdentified            = 0L
        val entriesProcessed             = 0L
        val extractCustomersPostResponse = ExtractCustomersPostResponse(
          entriesIdentified = entriesIdentified,
          entriesProcessed = entriesProcessed,
          customerIndividualCandidates = List.empty,
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              extractCustomersFileByteStream,
              extractCustomersFileName.value,
              SupportedMediaType.extractData,
              fileServiceConfig.fileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannedPath)
            .returns(ZIO.succeed(csvValidatedPath))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(CsvValidatedPath, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(extractCustomersPostResponse)),
        )
        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe extractCustomersPostResponse
        extractFromImageCallsRef.refValue shouldBe List.empty
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvValidatedPath, FileService.extractCustomersFromFileInstructions)
        )
      }

      "merge and deduplicate candidates from multiple CSV batches" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val extractCustomersFileName       = ExtractCustomersFileName.assume("customers.csv")
        val customerBookCsvBytes           = "Full Name,Email".getBytes(StandardCharsets.UTF_8)
        val extractCustomersFileByteStream = ZStream.fromIterable(customerBookCsvBytes)
        val fileScannedPath                = FileScannedPath(Files.createTempFile("file-service-spec-", ".csv"))
        fileScannedPath.value.toFile.deleteOnExit()
        val fileScannerScanOutput: FileScannerScanOutput = (
          fileScannedPath = fileScannedPath,
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerBookCsvPath = Files.createTempFile("file-service-spec-validated-", ".csv")
        Files.write(customerBookCsvPath, customerBookCsvBytes)
        customerBookCsvPath.toFile.deleteOnExit()
        val csvValidatedPath = CsvValidatedPath(customerBookCsvPath)

        val extractCustomerIndividualDataBatch1 = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("John Smith"),
            emails = List.empty,
            phoneNumbers = List.empty,
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
          ),
          isDuplicate = false,
          extractionNotes = None,
        )
        val extractCustomerIndividualDataBatch2 = extractCustomerIndividualDataBatch1

        val extractCustomersPostResponseBatch1 = ExtractCustomersPostResponse(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataBatch1),
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )
        val extractCustomersPostResponseBatch2 = ExtractCustomersPostResponse(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataBatch2),
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = Some("Second batch summary"),
        )

        inSequence(
          fileScannerMock.scan
            .expects(
              extractCustomersFileByteStream,
              extractCustomersFileName.value,
              SupportedMediaType.extractData,
              fileServiceConfig.fileBytesMax,
            )
            .returns(ZIO.succeed(fileScannerScanOutput))
            .once(),
          spreadsheetToolMock.convertToCsv
            .expects(fileScannedPath)
            .returns(ZIO.succeed(csvValidatedPath))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(CsvValidatedPath, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock[ExtractCustomersPostResponse](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          extractFromCsvOptResult = Some(
            ZIO.succeed(NonEmptyChunk(extractCustomersPostResponseBatch1, extractCustomersPostResponseBatch2))
          ),
        )
        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe ExtractCustomersPostResponse(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          customerIndividualCandidates = List(
            extractCustomerIndividualDataBatch1.copy(isDuplicate = true),
            extractCustomerIndividualDataBatch2.copy(isDuplicate = true),
          ),
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = Some("Second batch summary"),
        )
      }

      "propagate the validation error without calling AI when the scanner rejects the declared filename" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val extractCustomersFileName       = ExtractCustomersFileName.assume("customers.png")
        val extractCustomersFileByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")
        val scanError                      = ServiceError.BadRequestError.ValidationError(
          Seq(
            ServiceError.BadRequestError.InvalidFieldError(
              "fileNameDeclared",
              "File name declaration does not match detected media type [image/jpeg]",
              extractCustomersFileName.value,
            )
          )
        )

        fileScannerMock.scan
          .expects(
            extractCustomersFileByteStream,
            extractCustomersFileName.value,
            SupportedMediaType.extractData,
            fileServiceConfig.fileBytesMax,
          )
          .returns(ZIO.fail(scanError))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(CsvValidatedPath, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )
        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioError

        serviceError shouldBe scanError
        extractFromImageCallsRef.refValue shouldBe List.empty
        extractFromCsvCallsRef.refValue shouldBe List.empty
      }
    }
  }

  trait TestContext {
    val fileServiceConfig = FileServiceConfig(
      fileBytesMax = 5L * 1024 * 1024
    )

    val fileScannerMock                      = mock[FileScanner]
    val imageProcessingMock                  = mock[ImageProcessing]
    val spreadsheetToolMock                  = mock[SpreadsheetTool]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]
    val catalogueRepositoryMock              = mock[CatalogueRepository]
    val s3ClientOrganizationMediaMock        = mock[S3ClientOrganizationMedia]
    val aiClientMock                         = mock[AIClient]

    def buildFileService(aiClient: AIClient): FileService[ServiceTask] = ZIO
      .service[FileService[ServiceTask]]
      .provide(
        FileService.local,
        ZLayer.succeed(fileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(spreadsheetToolMock),
        ZLayer.succeed(organizationManagementRepositoryMock),
        ZLayer.succeed(catalogueRepositoryMock),
        ZLayer.succeed(aiClient),
        ZLayer.succeed(s3ClientOrganizationMediaMock),
      )
      .zioValue
  }
}
