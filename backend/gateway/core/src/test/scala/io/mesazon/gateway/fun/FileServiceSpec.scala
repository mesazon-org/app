package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.OrganizationS3Client
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
        val uploadedLogoResult: OrganizationS3Client.UploadedLogoResult =
          (
            organizationLogoOriginalBucketKey = organizationLogoOriginalBucketKey,
            organizationLogoNormalizedBucketKey = organizationLogoNormalizedBucketKey,
          )

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxOrganizationLogoBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          organizationS3ClientMock.uploadLogos
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedLogoResult)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationID,
              OrganizationStage.LogoProvided,
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
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxOrganizationLogoBytes)
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
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxOrganizationLogoBytes)
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
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxOrganizationLogoBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          organizationS3ClientMock.uploadLogos
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
        val uploadedLogoResult: OrganizationS3Client.UploadedLogoResult =
          (
            organizationLogoOriginalBucketKey = organizationLogoOriginalBucketKey,
            organizationLogoNormalizedBucketKey = organizationLogoNormalizedBucketKey,
          )

        val updateError = ServiceError.InternalServerError.UnexpectedError("Failed to persist organization logo")

        inSequence(
          fileScannerMock.scan
            .expects(organizationLogoByteStream, SupportedMediaTypes.images, fileServiceConfig.maxOrganizationLogoBytes)
            .returns(ZIO.succeed(scannedByteStream))
            .once(),
          imageProcessingMock.normalize
            .expects(scannedByteStream, SupportedMediaTypes.images)
            .returns(ZIO.succeed(normalizeResult))
            .once(),
          organizationS3ClientMock.uploadLogos
            .expects(organizationID, originalByteStream, normalizedByteStream)
            .returningZIO(uploadedLogoResult)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationID,
              OrganizationStage.LogoProvided,
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
  }

  trait TestContext {
    val fileServiceConfig = FileServiceConfig(
      maxOrganizationLogoBytes = 5L * 1024 * 1024
    )

    val fileScannerMock                      = mock[FileScanner]
    val imageProcessingMock                  = mock[ImageProcessing]
    val organizationS3ClientMock             = mock[OrganizationS3Client]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]

    def buildFileService: FileService[ServiceTask] = ZIO
      .service[FileService[ServiceTask]]
      .provide(
        FileService.local,
        ZLayer.succeed(fileServiceConfig),
        ZLayer.succeed(fileScannerMock),
        ZLayer.succeed(imageProcessingMock),
        ZLayer.succeed(organizationS3ClientMock),
        ZLayer.succeed(organizationManagementRepositoryMock),
      )
      .zioValue
  }
}
