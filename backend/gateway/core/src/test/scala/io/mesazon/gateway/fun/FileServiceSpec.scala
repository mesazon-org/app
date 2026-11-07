package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.FileServiceConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.nio.file.Files

class FileServiceSpec extends ZWordSpecBase, SmithyArbitraries, TokenArbitraries, RepositoryArbitraries {

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
              Some(organizationLogoImageAsset),
            )
            .returningZIO(arbitrarySample[OrganizationDetailsRow])
            .once(),
        )

        val fileService = buildFileService

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

        val fileService = buildFileService

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

        val fileService = buildFileService

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

        val fileService = buildFileService

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
              Some(organizationLogoImageAsset),
            )
            .failingZIO(updateError)
            .once(),
        )

        val fileService = buildFileService

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
  }

  trait TestContext {
    val fileServiceConfig = FileServiceConfig(
      fileBytesMax = 5L * 1024 * 1024
    )

    val fileScannerMock                      = mock[FileScanner]
    val imageProcessingMock                  = mock[ImageProcessing]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]
    val s3ClientOrganizationMediaMock        = mock[S3ClientOrganizationMedia]

    def buildFileService: FileService[ServiceTask] = ZIO
      .service[FileService[ServiceTask]]
      .provide(
        FileService.local,
        ZLayer.succeed(fileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(organizationManagementRepositoryMock),
        ZLayer.succeed(s3ClientOrganizationMediaMock),
      )
      .zioValue
  }
}
