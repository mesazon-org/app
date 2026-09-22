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
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import zio.*
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class FileServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

  "FileService" when {
    "uploadOrganizationLogo" should {
      "successfully scan, normalize, upload and persist the organization logo" in new TestContext {
        val organizationID                        = arbitrarySample[OrganizationID]
        val organizationLogoImageOriginalFileName = arbitrarySample[ImageOriginalFileName]
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/organization-image-test-1.jpeg")

        val originalByteStream = ImageOriginalByteStream(ZStream.fromResource("assets/organization-image-test-1.jpeg"))
        val normalizedByteStream =
          ImageNormalizedByteStream(ZStream.fromResource("assets/organization-image-test-2.webp"))
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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/organization-image-test-1.jpeg")

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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/organization-image-test-1.jpeg")

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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/organization-image-test-1.jpeg")

        val originalByteStream = ImageOriginalByteStream(ZStream.fromResource("assets/organization-image-test-1.jpeg"))
        val normalizedByteStream =
          ImageNormalizedByteStream(ZStream.fromResource("assets/organization-image-test-2.webp"))
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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val organizationLogoImageByteStream       = ZStream.fromResource("assets/organization-image-test-1.jpeg")

        val originalByteStream = ImageOriginalByteStream(ZStream.fromResource("assets/organization-image-test-1.jpeg"))
        val normalizedByteStream =
          ImageNormalizedByteStream(ZStream.fromResource("assets/organization-image-test-2.webp"))
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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/catalogue-image-test-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/catalogue-image-test-2.webp"))
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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(None)
            .once()
        )

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        inSequence(
          catalogueRepositoryMock.getCatalogueItem
            .expects(organizationID, catalogueItemID)
            .returningZIO(None)
            .once()
        )

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/catalogue-image-test-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/catalogue-image-test-2.webp"))
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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val catalogueItemImageByteStream       = ZStream.fromResource("assets/catalogue-image-test-1.jpeg")

        val catalogueItemRowActive = arbitrarySample[CatalogueItemRow].copy(
          organizationID = organizationID,
          catalogueItemID = catalogueItemID,
          status = CatalogueItemStatus.Active,
        )

        val originalByteStream   = ImageOriginalByteStream(ZStream.fromResource("assets/catalogue-image-test-1.jpeg"))
        val normalizedByteStream = ImageNormalizedByteStream(ZStream.fromResource("assets/catalogue-image-test-2.webp"))
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

        val fileService = buildFileService(aiClientDataExtractionMock)

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
        val extractCustomersFileByteStream = ZStream.fromResource("assets/contact-book-image-test-11.jpeg")
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
          emptyEntryRows = List.empty,
          unidentifiedEntryRows = List.empty,
          unidentifiedEntriesNotes = None,
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
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(extractCustomersPostResponse)),
        )
        val fileService = buildFileService(aiClientDataExtraction)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe extractCustomersPostResponse
        extractFromImageCallsRef.refValue shouldBe List(
          (fileScannedPath, SupportedMediaType.JPEG, AIInstructions.extractCustomersFromImageInstructions)
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
        val csvRecordsFixture: List[CSVRecord] =
          CSVParser.parse("Full Name,Email", CSVFormat.DEFAULT).getRecords.asScala.toList
        val entriesIdentified            = 0L
        val entriesProcessed             = 0L
        val extractCustomersPostResponse = ExtractCustomersPostResponse(
          entriesIdentified = entriesIdentified,
          entriesProcessed = entriesProcessed,
          customerIndividualCandidates = List.empty,
          customerBusinessCandidates = List.empty,
          emptyEntryRows = List.empty,
          unidentifiedEntryRows = List.empty,
          unidentifiedEntriesNotes = None,
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
            .returns(ZStream.fromIterable(csvRecordsFixture))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromImageOptResult = Some(ZIO.succeed(extractCustomersPostResponse)),
        )
        val fileService = buildFileService(aiClientDataExtraction)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe extractCustomersPostResponse
        extractFromImageCallsRef.refValue shouldBe List.empty
        extractFromCsvCallsRef.refValue shouldBe List(
          (csvRecordsFixture, AIInstructions.extractCustomersFromFileInstructions)
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
        val csvRecordsFixture: List[CSVRecord] =
          CSVParser.parse("Full Name,Email", CSVFormat.DEFAULT).getRecords.asScala.toList

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
          emptyEntryRows = List(3L),
          unidentifiedEntryRows = List(7L),
          unidentifiedEntriesNotes = None,
        )
        val extractCustomersPostResponseBatch2 = ExtractCustomersPostResponse(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataBatch2),
          customerBusinessCandidates = List.empty,
          emptyEntryRows = List(12L),
          unidentifiedEntryRows = List(20L),
          unidentifiedEntriesNotes = Some("Second batch summary"),
        )
        val noteCompactionOutputExpected =
          NoteCompactionOutput(note = "Rows 3 and 12 were blank; rows 7 and 20 had no readable name.")

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
            .returns(ZStream.fromIterable(csvRecordsFixture))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[ExtractCustomersPostResponse](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(
            ZIO.succeed(NonEmptyChunk(extractCustomersPostResponseBatch1, extractCustomersPostResponseBatch2))
          ),
          noteCompactionOptResult = Some(ZIO.succeed(noteCompactionOutputExpected)),
        )
        val fileService = buildFileService(aiClientDataExtraction)

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
          emptyEntryRows = List(3L, 12L),
          unidentifiedEntryRows = List(7L, 20L),
          unidentifiedEntriesNotes = Some(noteCompactionOutputExpected.note),
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (
            2L,
            2L,
            List(3L, 12L),
            List(7L, 20L),
            List("Second batch summary"),
            AIInstructions.noteCompactionInstructions,
          )
        )
      }

      "successfully use a local fallback message when note compaction fails" in new TestContext {
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
        val csvRecordsFixture: List[CSVRecord] =
          CSVParser.parse("Full Name,Email", CSVFormat.DEFAULT).getRecords.asScala.toList

        val extractCustomerIndividualDataFallback = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Jane Roe"),
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

        val extractCustomersPostResponseFallback = ExtractCustomersPostResponse(
          entriesIdentified = 4L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataFallback),
          customerBusinessCandidates = List.empty,
          emptyEntryRows = List(3L, 5L),
          unidentifiedEntryRows = List(9L),
          unidentifiedEntriesNotes = Some("A raw batch note the fallback must not repeat"),
        )
        val noteCompactionErrorExpected =
          ServiceError.InternalServerError.UnexpectedError("Unable to send message to AI")

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
            .returns(ZStream.fromIterable(csvRecordsFixture))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[ExtractCustomersPostResponse](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk(extractCustomersPostResponseFallback))),
          noteCompactionOptResult = Some(ZIO.fail(noteCompactionErrorExpected)),
        )
        val fileService = buildFileService(aiClientDataExtraction)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe ExtractCustomersPostResponse(
          entriesIdentified = 4L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataFallback),
          customerBusinessCandidates = List.empty,
          emptyEntryRows = List(3L, 5L),
          unidentifiedEntryRows = List(9L),
          unidentifiedEntriesNotes =
            Some("1 of 4 entries could be added automatically. Rows 3, 5 were blank. Rows 9 had no name we could read."),
        )
        noteCompactionCallsRef.refValue shouldBe List(
          (
            4L,
            1L,
            List(3L, 5L),
            List(9L),
            List("A raw batch note the fallback must not repeat"),
            AIInstructions.noteCompactionInstructions,
          )
        )
      }

      "successfully skip note compaction when there is nothing to report" in new TestContext {
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
        val csvRecordsFixture: List[CSVRecord] =
          CSVParser.parse("Full Name,Email", CSVFormat.DEFAULT).getRecords.asScala.toList

        val extractCustomerIndividualDataClean = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Alice Clean"),
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

        val extractCustomersPostResponseClean = ExtractCustomersPostResponse(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataClean),
          customerBusinessCandidates = List.empty,
          emptyEntryRows = List.empty,
          unidentifiedEntryRows = List.empty,
          unidentifiedEntriesNotes = None,
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
            .returns(ZStream.fromIterable(csvRecordsFixture))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[ExtractCustomersPostResponse](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk(extractCustomersPostResponseClean))),
        )
        val fileService = buildFileService(aiClientDataExtraction)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe extractCustomersPostResponseClean
        noteCompactionCallsRef.refValue shouldBe List.empty
      }

      "successfully skip note compaction when row lists have entries but no batch wrote a note" in new TestContext {
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
        val csvRecordsFixture: List[CSVRecord] =
          CSVParser.parse("Full Name,Email", CSVFormat.DEFAULT).getRecords.asScala.toList

        val extractCustomerIndividualDataNoNotes = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Bob NoNotes"),
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

        val extractCustomersPostResponseNoNotes = ExtractCustomersPostResponse(
          entriesIdentified = 3L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataNoNotes),
          customerBusinessCandidates = List.empty,
          emptyEntryRows = List(3L),
          unidentifiedEntryRows = List(4L, 5L),
          unidentifiedEntriesNotes = None,
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
            .returns(ZStream.fromIterable(csvRecordsFixture))
            .once(),
        )

        val extractFromImageCallsRef =
          Ref.make(List.empty[(FileScannedPath, SupportedMediaType, String)]).zioValue
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock[ExtractCustomersPostResponse](
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
          extractFromCsvOptResult = Some(ZIO.succeed(NonEmptyChunk(extractCustomersPostResponseNoNotes))),
        )
        val fileService = buildFileService(aiClientDataExtraction)

        val response = fileService
          .extractCustomers(organizationID, extractCustomersFileName, extractCustomersFileByteStream)
          .zioValue

        response shouldBe extractCustomersPostResponseNoNotes
        noteCompactionCallsRef.refValue shouldBe List.empty
      }

      "propagate the validation error without calling AI when the scanner rejects the declared filename" in new TestContext {
        val organizationID                 = arbitrarySample[OrganizationID]
        val extractCustomersFileName       = ExtractCustomersFileName.assume("customers.png")
        val extractCustomersFileByteStream = ZStream.fromResource("assets/contact-book-image-test-11.jpeg")
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
        val extractFromCsvCallsRef = Ref.make(List.empty[(List[CSVRecord], String)]).zioValue
        val noteCompactionCallsRef =
          Ref.make(List.empty[(Long, Long, List[Long], List[Long], List[String], String)]).zioValue
        val aiClientDataExtraction = new Mocks.AIClientDataExtractionMock(
          extractFromImageCallsRef,
          extractFromCsvCallsRef,
          noteCompactionCallsRef,
        )
        val fileService = buildFileService(aiClientDataExtraction)

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
    val aiClientDataExtractionMock           = mock[AIClientDataExtraction]

    def buildFileService(aiClientDataExtraction: AIClientDataExtraction): FileService[ServiceTask] = ZIO
      .service[FileService[ServiceTask]]
      .provide(
        FileService.local,
        ZLayer.succeed(fileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(spreadsheetToolMock),
        ZLayer.succeed(organizationManagementRepositoryMock),
        ZLayer.succeed(catalogueRepositoryMock),
        ZLayer.succeed(aiClientDataExtraction),
        ZLayer.succeed(s3ClientOrganizationMediaMock),
      )
      .zioValue
  }
}
