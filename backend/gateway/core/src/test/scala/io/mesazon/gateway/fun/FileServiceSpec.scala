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

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
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
            .expects(organizationLogoImageByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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
            .expects(organizationLogoImageByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
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

        val scannedByteStream = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizeError = ServiceError.InternalServerError.UnexpectedError("Failed to normalize organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoImageByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/test-logo-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/test-logo-2.webp"))
        val normalizeResult: NormalizeResult =
          (imageOriginalByteStream = originalByteStream, imageNormalizedByteStream = normalizedByteStream)

        val uploadError = ServiceError.InternalServerError.UnexpectedError("Failed to upload organization logo to S3")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoImageByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
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
            .expects(organizationLogoImageByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
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
              SupportedMediaType.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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
              SupportedMediaType.images,
              fileServiceConfig.maxUploadBytes,
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
              SupportedMediaType.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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
              SupportedMediaType.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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

        val scannedByteStream    = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg"))
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
              SupportedMediaType.images,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(
              ZIO.succeed(
                (
                  fileByteStreamScanned = scannedByteStream,
                  supportedMediaType = SupportedMediaType.JPEG,
                  fileBytesSize = FileBytesSize.assume(1L),
                )
              )
            )
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaType.images)
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
        val organizationID         = arbitrarySample[OrganizationID]
        val fileNameDeclared       = FileNameDeclared.assume("customers.jpeg")
        val customerBookByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")
        val fileScannedPath        = FileScannedPath(Files.createTempFile("file-service-spec-", ".jpeg"))
        fileScannedPath.value.toFile.deleteOnExit()
        val fileScannerScanV1Output: FileScannerScanV1Output = (
          fileScannedPath = fileScannedPath,
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val entriesIdentified        = 0L
        val entriesProcessed         = 0L
        val extractCustomersResponse = ExtractCustomersResponse(
          entriesIdentified = entriesIdentified,
          entriesProcessed = entriesProcessed,
          customerIndividualCandidates = List.empty,
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )

        fileScannerMock.scanV1
          .expects(
            customerBookByteStream,
            fileNameDeclared,
            SupportedMediaType.extractData,
            fileServiceConfig.maxUploadBytes,
          )
          .returns(ZIO.succeed(fileScannerScanV1Output))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(CsvValidatedPath, String)]).zioValue
        val aiClient               = new Mocks.AIClientPathMock(
          ZIO.succeed(extractCustomersResponse),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )
        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomers(organizationID, fileNameDeclared, customerBookByteStream)
          .zioValue

        response shouldBe extractCustomersResponse
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, FileService.extractCustomersFromPhotoInstructions)
        )
        extractFromCsvCallsRef.refValue shouldBe List.empty
      }

      "return the AI's extracted candidates for a spreadsheet after CSV conversion" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val fileNameDeclared       = FileNameDeclared.assume("customers.csv")
        val customerBookCsvBytes   = "Full Name,Email".getBytes(StandardCharsets.UTF_8)
        val customerBookByteStream = ZStream.fromIterable(customerBookCsvBytes)
        val fileScannedPath        = FileScannedPath(Files.createTempFile("file-service-spec-", ".csv"))
        fileScannedPath.value.toFile.deleteOnExit()
        val fileScannerScanV1Output: FileScannerScanV1Output = (
          fileScannedPath = fileScannedPath,
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )
        val customerBookCsvPath = Files.createTempFile("file-service-spec-validated-", ".csv")
        Files.write(customerBookCsvPath, customerBookCsvBytes)
        customerBookCsvPath.toFile.deleteOnExit()
        val csvValidatedPath         = CsvValidatedPath(customerBookCsvPath)
        val entriesIdentified        = 0L
        val entriesProcessed         = 0L
        val extractCustomersResponse = ExtractCustomersResponse(
          entriesIdentified = entriesIdentified,
          entriesProcessed = entriesProcessed,
          customerIndividualCandidates = List.empty,
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )

        inSequence(
          fileScannerMock.scanV1
            .expects(
              customerBookByteStream,
              fileNameDeclared,
              SupportedMediaType.extractData,
              fileServiceConfig.maxUploadBytes,
            )
            .returns(ZIO.succeed(fileScannerScanV1Output))
            .once(),
          spreadsheetToolMock.validateAndConvertToCsv
            .expects(fileScannedPath, SupportedMediaType.CSV)
            .returns(ZIO.succeed(csvValidatedPath))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(CsvValidatedPath, String)]).zioValue
        val aiClient               = new Mocks.AIClientPathMock(
          ZIO.succeed(extractCustomersResponse),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )
        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomers(organizationID, fileNameDeclared, customerBookByteStream)
          .zioValue

        response shouldBe extractCustomersResponse
        extractFromImageCallsRef.refValue shouldBe List.empty
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvValidatedPath, FileService.extractCustomersFromFileInstructions)
        )
      }

      "propagate the validation error without calling AI when the scanner rejects the declared filename" in new TestContext {
        val organizationID         = arbitrarySample[OrganizationID]
        val fileNameDeclared       = FileNameDeclared.assume("customers.png")
        val customerBookByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")
        val scanError              = ServiceError.BadRequestError.ValidationError(
          Seq(
            ServiceError.BadRequestError.InvalidFieldError(
              "fileNameDeclared",
              "File name declaration does not match detected media type [image/jpeg]",
              fileNameDeclared.value,
            )
          )
        )

        fileScannerMock.scanV1
          .expects(
            customerBookByteStream,
            fileNameDeclared,
            SupportedMediaType.extractData,
            fileServiceConfig.maxUploadBytes,
          )
          .returns(ZIO.fail(scanError))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.die(new NotImplementedError("AIClient extraction should not be called")),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )
        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomers(organizationID, fileNameDeclared, customerBookByteStream)
          .zioError

        serviceError shouldBe scanError
        extractFromImageCallsRef.refValue shouldBe List.empty
        extractFromCsvCallsRef.refValue shouldBe List.empty
      }
    }

    "extractCustomersFromPhoto" should {
      "return the AI's extracted candidates for a scanned photo" in new TestContext {
        val organizationID              = arbitrarySample[OrganizationID]
        val customerBookPhotoByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val customerBookPhotoScanOutput: FileScannerScanOutput = (
          fileByteStreamScanned = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )

        val extractCustomerPhoneNumberIndividual = ExtractCustomerPhoneNumber(
          phoneNationalNumber = arbitrarySample[PhoneNationalNumber],
          phoneCountryCode = arbitrarySample[PhoneCountryCode],
        )
        val extractCustomerIndividual = ExtractCustomerIndividual(
          fullName = arbitrarySample[CustomerFullName],
          emails = List(
            ExtractCustomerEmailEntry(
              email = arbitrarySample[CustomerEmail],
              isDefault = true,
            )
          ),
          phoneNumbers = List(
            ExtractCustomerPhoneNumberEntry(
              phoneNumber = extractCustomerPhoneNumberIndividual,
              isDefault = true,
            )
          ),
          addressLine1 = None,
          addressLine2 = None,
          city = None,
          postalCode = None,
          country = None,
        )
        val extractCustomerIndividualData = ExtractCustomerIndividualData(
          candidate = extractCustomerIndividual,
          isDuplicate = false,
          extractionNotes = None,
        )

        val extractCustomerPhoneNumberBusiness = ExtractCustomerPhoneNumber(
          phoneNationalNumber = arbitrarySample[PhoneNationalNumber],
          phoneCountryCode = arbitrarySample[PhoneCountryCode],
        )
        val extractCustomerBusiness = ExtractCustomerBusiness(
          businessName = arbitrarySample[CustomerBusinessName],
          emails = List(
            ExtractCustomerEmailEntry(
              email = arbitrarySample[CustomerEmail],
              isDefault = true,
            )
          ),
          taxID = None,
          phoneNumbers = List(
            ExtractCustomerPhoneNumberEntry(
              phoneNumber = extractCustomerPhoneNumberBusiness,
              isDefault = true,
            )
          ),
          addressLine1 = None,
          addressLine2 = None,
          city = None,
          postalCode = None,
          country = None,
          customerBusinessContacts = List(
            ExtractCustomerBusinessContact(
              fullName = arbitrarySample[CustomerFullName],
              role = Some(arbitrarySample[CustomerBusinessContactRole]),
              email = Some(arbitrarySample[CustomerEmail]),
              phoneNumber = Some(extractCustomerPhoneNumberBusiness),
            )
          ),
        )
        val extractCustomerBusinessData = ExtractCustomerBusinessData(
          candidate = extractCustomerBusiness,
          isDuplicate = false,
          extractionNotes = None,
        )

        val extractCustomersResponse = ExtractCustomersResponse(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          customerIndividualCandidates = List(extractCustomerIndividualData),
          customerBusinessCandidates = List(extractCustomerBusinessData),
          unidentifiedEntriesSummary = None,
        )

        fileScannerMock.scan
          .expects(customerBookPhotoByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
          .returns(ZIO.succeed(customerBookPhotoScanOutput))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.succeed(extractCustomersResponse),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomersFromPhoto(organizationID, customerBookPhotoByteStream)
          .zioValue

        response shouldBe extractCustomersResponse

        extractFromImageCallsRef.refValue shouldBe List(
          (
            customerBookPhotoScanOutput.fileByteStreamScanned,
            customerBookPhotoScanOutput.supportedMediaType,
            FileService.extractCustomersFromPhotoInstructions,
          )
        )

        val extractCustomersFromPhotoInstructionsNormalized =
          extractFromImageCallsRef.refValue.head._3.replaceAll("\\s+", " ")

        List(
          "best-effort attempt to provide a valid phone pair",
          "phoneNationalNumber must contain only the national number, without the country code",
          "{\"phoneNationalNumber\":\"99123456\",\"phoneCountryCode\":\"+357\"}",
          "{\"phoneNationalNumber\":\"4155550123\",\"phoneCountryCode\":\"+1\"}",
          "Email and phone lists may be empty",
          "Whenever either list is non-empty, mark exactly one entry in that list with isDefault=true",
          "If you cannot make out any name for an entry, do not return it as a candidate",
          "only include a business contact if you can make out that contact's name",
        ).foreach(instruction => extractCustomersFromPhotoInstructionsNormalized.contains(instruction) shouldBe true)
      }

      "propagate the error when the photo fails FileScanner's scan (unsupported type or too large)" in new TestContext {
        val organizationID              = arbitrarySample[OrganizationID]
        val customerBookPhotoByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val scanError = ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported file types are: [image/png, image/jpeg, image/webp]"
        )

        fileScannerMock.scan
          .expects(customerBookPhotoByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
          .returns(ZIO.fail(scanError))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.die(new NotImplementedError("AIClient.extractFromImage should not be called")),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomersFromPhoto(organizationID, customerBookPhotoByteStream)
          .zioError

        serviceError shouldBe scanError

        extractFromImageCallsRef.refValue shouldBe List.empty
      }

      "propagate the error when AIClient.extractFromImage fails" in new TestContext {
        val organizationID              = arbitrarySample[OrganizationID]
        val customerBookPhotoByteStream = ZStream.fromResource("assets/test-logo-1.jpeg")

        val customerBookPhotoScanOutput: FileScannerScanOutput = (
          fileByteStreamScanned = FileByteStreamScanned(ZStream.fromResource("assets/test-logo-1.jpeg")),
          supportedMediaType = SupportedMediaType.JPEG,
          fileBytesSize = FileBytesSize.assume(1L),
        )

        val aiClientError = ServiceError.InternalServerError.UnexpectedError("Unable to send message to AI")

        fileScannerMock.scan
          .expects(customerBookPhotoByteStream, SupportedMediaType.images, fileServiceConfig.maxUploadBytes)
          .returns(ZIO.succeed(customerBookPhotoScanOutput))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.fail(aiClientError),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomersFromPhoto(organizationID, customerBookPhotoByteStream)
          .zioError

        serviceError shouldBe aiClientError

        extractFromImageCallsRef.refValue shouldBe List(
          (
            customerBookPhotoScanOutput.fileByteStreamScanned,
            customerBookPhotoScanOutput.supportedMediaType,
            FileService.extractCustomersFromPhotoInstructions,
          )
        )
      }
    }

    "extractCustomersFromFile" should {
      "return the AI's extracted candidates for a scanned CSV file" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBookFileByteStream =
          ZStream.fromIterable(
            "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\n".getBytes(StandardCharsets.UTF_8)
          )

        val customerBookFileScanOutput: FileScannerScanOutput = (
          fileByteStreamScanned = FileByteStreamScanned(
            ZStream.fromIterable(
              "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\n".getBytes(StandardCharsets.UTF_8)
            )
          ),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )

        val customerBookFileValidatedCsv = ValidatedCsvByteStream(
          ZStream.fromIterable(
            "Full Name,Email\r\nJohn Smith,john.smith@example.com\r\n".getBytes(StandardCharsets.UTF_8)
          )
        )

        val extractCustomersResponse = ExtractCustomersResponse(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(
            ExtractCustomerIndividualData(
              candidate = ExtractCustomerIndividual(
                fullName = arbitrarySample[CustomerFullName],
                emails = List(
                  ExtractCustomerEmailEntry(email = arbitrarySample[CustomerEmail], isDefault = true)
                ),
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
          ),
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )

        inSequence(
          fileScannerMock.scan
            .expects(customerBookFileByteStream, SupportedMediaType.spreadsheets, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(customerBookFileScanOutput))
            .once(),
          spreadsheetToolMock.convertValidateCsv
            .expects(customerBookFileScanOutput.fileByteStreamScanned, SupportedMediaType.CSV)
            .returns(ZIO.succeed(customerBookFileValidatedCsv))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.succeed(extractCustomersResponse),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomersFromFile(organizationID, customerBookFileByteStream)
          .zioValue

        response shouldBe extractCustomersResponse

        extractFromCsvCallsRef.refValue shouldBe List(
          (customerBookFileValidatedCsv, FileService.extractCustomersFromFileInstructions)
        )

        val extractCustomersFromFileInstructionsNormalized =
          extractFromCsvCallsRef.refValue.head._2.replaceAll("\\s+", " ")

        List(
          "There is no fixed column layout",
          "any column you don't recognize is simply ignored",
          "a completely blank row is not an entry at all",
          "only include a business contact if you can make out that contact's name",
        ).foreach(instruction => extractCustomersFromFileInstructionsNormalized.contains(instruction) shouldBe true)
      }

      "return the AI's extracted candidates for a scanned Excel file via the converter" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBookFileByteStream = ZStream.fromResource("assets/test-customers.xlsx")

        val customerBookFileScanOutput: FileScannerScanOutput = (
          fileByteStreamScanned = FileByteStreamScanned(ZStream.fromResource("assets/test-customers.xlsx")),
          supportedMediaType = SupportedMediaType.XLSX,
          fileBytesSize = FileBytesSize.assume(1L),
        )

        val customerBookFileValidatedCsv = ValidatedCsvByteStream(
          ZStream.fromIterable("Full Name,Email\r\nJane Doe,jane.doe@example.com\r\n".getBytes(StandardCharsets.UTF_8))
        )

        val extractCustomersResponse = ExtractCustomersResponse(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(
            ExtractCustomerIndividualData(
              candidate = ExtractCustomerIndividual(
                fullName = arbitrarySample[CustomerFullName],
                emails = List(
                  ExtractCustomerEmailEntry(email = arbitrarySample[CustomerEmail], isDefault = true)
                ),
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
          ),
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )

        inSequence(
          fileScannerMock.scan
            .expects(customerBookFileByteStream, SupportedMediaType.spreadsheets, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(customerBookFileScanOutput))
            .once(),
          spreadsheetToolMock.convertValidateCsv
            .expects(customerBookFileScanOutput.fileByteStreamScanned, SupportedMediaType.XLSX)
            .returns(ZIO.succeed(customerBookFileValidatedCsv))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.succeed(extractCustomersResponse),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val response = fileService
          .extractCustomersFromFile(organizationID, customerBookFileByteStream)
          .zioValue

        response shouldBe extractCustomersResponse

        extractFromCsvCallsRef.refValue shouldBe List(
          (customerBookFileValidatedCsv, FileService.extractCustomersFromFileInstructions)
        )
      }

      "propagate the error when the file fails FileScanner's scan (unsupported type or too large)" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBookFileByteStream = ZStream.fromIterable("Full Name,Email".getBytes(StandardCharsets.UTF_8))

        val scanError = ServiceError.InternalServerError.UnexpectedError(
          "Unsupported file type: [text/plain]. Supported file types are: [text/csv, text/plain, application/vnd.ms-excel, application/vnd.openxmlformats-officedocument.spreadsheetml.sheet]"
        )

        fileScannerMock.scan
          .expects(customerBookFileByteStream, SupportedMediaType.spreadsheets, fileServiceConfig.maxUploadBytes)
          .returns(ZIO.fail(scanError))
          .once()

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.die(new NotImplementedError("AIClient.extractFromCsv should not be called")),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomersFromFile(organizationID, customerBookFileByteStream)
          .zioError

        serviceError shouldBe scanError

        extractFromCsvCallsRef.refValue shouldBe List.empty
      }

      "propagate the error when the SpreadsheetTool rejects a structurally invalid file" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBookFileByteStream = ZStream.fromIterable("Full Name,Email".getBytes(StandardCharsets.UTF_8))

        val customerBookFileScanOutput: FileScannerScanOutput = (
          fileByteStreamScanned =
            FileByteStreamScanned(ZStream.fromIterable("Full Name,Email".getBytes(StandardCharsets.UTF_8))),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )

        val csvValidityError = ServiceError.InternalServerError.UnexpectedError("File is not valid CSV")

        inSequence(
          fileScannerMock.scan
            .expects(customerBookFileByteStream, SupportedMediaType.spreadsheets, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(customerBookFileScanOutput))
            .once(),
          spreadsheetToolMock.convertValidateCsv
            .expects(customerBookFileScanOutput.fileByteStreamScanned, SupportedMediaType.CSV)
            .returns(ZIO.fail(csvValidityError))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient               = new Mocks.AIClientMock(
          ZIO.die(new NotImplementedError("AIClient.extractFromCsv should not be called")),
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
        )

        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomersFromFile(organizationID, customerBookFileByteStream)
          .zioError

        serviceError shouldBe csvValidityError

        extractFromCsvCallsRef.refValue shouldBe List.empty
      }

      "propagate the error when AIClient.extractFromCsv fails" in new TestContext {
        val organizationID             = arbitrarySample[OrganizationID]
        val customerBookFileByteStream = ZStream.fromIterable("Full Name,Email".getBytes(StandardCharsets.UTF_8))

        val customerBookFileScanOutput: FileScannerScanOutput = (
          fileByteStreamScanned =
            FileByteStreamScanned(ZStream.fromIterable("Full Name,Email".getBytes(StandardCharsets.UTF_8))),
          supportedMediaType = SupportedMediaType.CSV,
          fileBytesSize = FileBytesSize.assume(1L),
        )

        val customerBookFileValidatedCsv =
          ValidatedCsvByteStream(ZStream.fromIterable("Full Name,Email".getBytes(StandardCharsets.UTF_8)))

        val aiClientError = ServiceError.InternalServerError.UnexpectedError("Unable to send message to AI")

        inSequence(
          fileScannerMock.scan
            .expects(customerBookFileByteStream, SupportedMediaType.spreadsheets, fileServiceConfig.maxUploadBytes)
            .returns(ZIO.succeed(customerBookFileScanOutput))
            .once(),
          spreadsheetToolMock.convertValidateCsv
            .expects(customerBookFileScanOutput.fileByteStreamScanned, SupportedMediaType.CSV)
            .returns(ZIO.succeed(customerBookFileValidatedCsv))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileByteStreamScanned, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(ValidatedCsvByteStream, String)]).zioValue
        val aiClient = new Mocks.AIClientMock(ZIO.fail(aiClientError), extractFromImageCallsRef, extractFromCsvCallsRef)

        val fileService = buildFileService(aiClient)

        val serviceError = fileService
          .extractCustomersFromFile(organizationID, customerBookFileByteStream)
          .zioError

        serviceError shouldBe aiClientError

        extractFromCsvCallsRef.refValue shouldBe List(
          (customerBookFileValidatedCsv, FileService.extractCustomersFromFileInstructions)
        )
      }
    }
  }

  trait TestContext {
    val fileServiceConfig = FileServiceConfig(
      maxUploadBytes = 5L * 1024 * 1024
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
