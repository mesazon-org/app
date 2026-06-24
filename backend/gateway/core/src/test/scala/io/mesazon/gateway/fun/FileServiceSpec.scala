package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.OrganizationS3Client
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.ZStream

import java.time.Instant
import java.time.temporal.ChronoUnit

class FileServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries, TokenArbitraries {

  "FileService" when {
    "uploadOrganizationLogo" should {
      "successfully process organization logo upload request" in new TestContext {
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoFileName = None,
            logoBucketKey = None,
          )
        val organizationStage         = OrganizationStage.LogoProvided
        val organizationLogoFileName  = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBytes     = ZStream.fromResource("assets/test-logo-1.jpeg")
        val organizationLogoBucketKey = arbitrarySample[OrganizationLogoBucketKey]

        inSequence(
          organizationManagementRepositoryMock.getOrganization
            .expects(organizationDetailsRow.organizationID)
            .returningZIO(Some(organizationDetailsRow))
            .once(),
          organizationS3ClientMock.uploadLogos
            .expects(organizationDetailsRow.organizationID, organizationLogoFileName, organizationLogoBytes)
            .returningZIO(organizationLogoBucketKey)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationDetailsRow.organizationID,
              organizationStage,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              Some(organizationLogoBucketKey),
              Some(organizationLogoFileName),
            )
            .returningZIO(
              organizationDetailsRow.copy(
                organizationStage = organizationStage,
                logoFileName = Some(organizationLogoFileName),
                logoBucketKey = Some(organizationLogoBucketKey),
              )
            )
            .once(),
        )

        val fileService = buildFileService

        val response = fileService
          .uploadOrganizationLogo(
            organizationID = organizationDetailsRow.organizationID,
            organizationLogoFileName = organizationLogoFileName,
            organizationLogoFile = organizationLogoBytes,
          )
          .zioEither

        assert(response.isRight)
      }

      "successfully process request organization logo upload when logo already exist" in new TestContext {
        val organizationStage = OrganizationStage.LogoProvided

        val organizationLogoFileName1  = OrganizationLogoFileName.assume("test-logo-1.jpeg")
        val organizationLogoBucketKey1 = arbitrarySample[OrganizationLogoBucketKey]

        val organizationLogoFileName2  = OrganizationLogoFileName.assume("test-logo-2.jpeg")
        val organizationLogoBucketKey2 = arbitrarySample[OrganizationLogoBucketKey]

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoFileName = Some(organizationLogoFileName1),
            logoBucketKey = Some(organizationLogoBucketKey1),
          )
        val organizationLogoBytes = ZStream.fromResource("assets/test-logo-1.jpeg")

        inSequence(
          organizationManagementRepositoryMock.getOrganization
            .expects(organizationDetailsRow.organizationID)
            .returningZIO(Some(organizationDetailsRow))
            .once(),
          organizationS3ClientMock.uploadLogos
            .expects(organizationDetailsRow.organizationID, organizationLogoFileName2, organizationLogoBytes)
            .returningZIO(organizationLogoBucketKey2)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationDetailsRow.organizationID,
              organizationStage,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              Some(organizationLogoBucketKey2),
              Some(organizationLogoFileName2),
            )
            .returningZIO(
              organizationDetailsRow.copy(
                organizationStage = organizationStage,
                logoFileName = Some(organizationLogoFileName2),
                logoBucketKey = Some(organizationLogoBucketKey2),
              )
            )
            .once(),
          organizationS3ClientMock.deleteLogo
            .expects(organizationLogoBucketKey1)
            .returningZIOUnit
            .once(),
        )

        val fileService = buildFileService

        val response = fileService
          .uploadOrganizationLogo(
            organizationID = organizationDetailsRow.organizationID,
            organizationLogoFileName = organizationLogoFileName2,
            organizationLogoFile = organizationLogoBytes,
          )
          .zioEither

        assert(response.isRight)
      }

      "successfully process request organization logo upload even when delete old logo fails" in new TestContext {
        val organizationStage = OrganizationStage.LogoProvided

        val organizationLogoFileName1  = OrganizationLogoFileName.assume("test-logo-1.jpeg")
        val organizationLogoBucketKey1 = arbitrarySample[OrganizationLogoBucketKey]

        val organizationLogoFileName2  = OrganizationLogoFileName.assume("test-logo-2.jpeg")
        val organizationLogoBucketKey2 = arbitrarySample[OrganizationLogoBucketKey]

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoFileName = Some(organizationLogoFileName1),
            logoBucketKey = Some(organizationLogoBucketKey1),
          )
        val organizationLogoBytes = ZStream.fromResource("assets/test-logo-1.jpeg")

        inSequence(
          organizationManagementRepositoryMock.getOrganization
            .expects(organizationDetailsRow.organizationID)
            .returningZIO(Some(organizationDetailsRow))
            .once(),
          organizationS3ClientMock.uploadLogos
            .expects(organizationDetailsRow.organizationID, organizationLogoFileName2, organizationLogoBytes)
            .returningZIO(organizationLogoBucketKey2)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationDetailsRow.organizationID,
              organizationStage,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              Some(organizationLogoBucketKey2),
              Some(organizationLogoFileName2),
            )
            .returningZIO(
              organizationDetailsRow.copy(
                organizationStage = organizationStage,
                logoFileName = Some(organizationLogoFileName2),
                logoBucketKey = Some(organizationLogoBucketKey2),
              )
            )
            .once(),
          organizationS3ClientMock.deleteLogo
            .expects(organizationLogoBucketKey1)
            .returns(ZIO.fail(ServiceError.InternalServerError.UnexpectedError("Failed to delete old logo")))
            .once(),
        )

        val fileService = buildFileService

        val response = fileService
          .uploadOrganizationLogo(
            organizationID = organizationDetailsRow.organizationID,
            organizationLogoFileName = organizationLogoFileName2,
            organizationLogoFile = organizationLogoBytes,
          )
          .zioEither

        assert(response.isRight)
      }

      "successfully process request organization logo upload when already exist with the same name" in new TestContext {
        val organizationStage         = OrganizationStage.LogoProvided
        val organizationLogoFileName  = arbitrarySample[OrganizationLogoFileName]
        val organizationLogoBucketKey = arbitrarySample[OrganizationLogoBucketKey]

        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(
            logoFileName = Some(organizationLogoFileName),
            logoBucketKey = Some(organizationLogoBucketKey),
          )
        val organizationLogoBytes = ZStream.fromResource("assets/test-logo-1.jpeg")

        inSequence(
          organizationManagementRepositoryMock.getOrganization
            .expects(organizationDetailsRow.organizationID)
            .returningZIO(Some(organizationDetailsRow))
            .once(),
          organizationS3ClientMock.uploadLogos
            .expects(organizationDetailsRow.organizationID, organizationLogoFileName, organizationLogoBytes)
            .returningZIO(organizationLogoBucketKey)
            .once(),
          organizationManagementRepositoryMock.updateOrganization
            .expects(
              organizationDetailsRow.organizationID,
              organizationStage,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              None,
              Some(organizationLogoBucketKey),
              Some(organizationLogoFileName),
            )
            .returningZIO(
              organizationDetailsRow.copy(
                organizationStage = organizationStage,
                logoFileName = Some(organizationLogoFileName),
                logoBucketKey = Some(organizationLogoBucketKey),
              )
            )
            .once(),
        )

        val fileService = buildFileService

        val response = fileService
          .uploadOrganizationLogo(
            organizationID = organizationDetailsRow.organizationID,
            organizationLogoFileName = organizationLogoFileName,
            organizationLogoFile = organizationLogoBytes,
          )
          .zioEither

        assert(response.isRight)
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now.truncatedTo(ChronoUnit.MILLIS)

    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]
    val organizationS3ClientMock             = mock[OrganizationS3Client]

    def buildFileService: FileService[ServiceTask] = ZIO
      .service[FileService[ServiceTask]]
      .provide(
        FileService.local,
        ZLayer.succeed(organizationManagementRepositoryMock),
        ZLayer.succeed(organizationS3ClientMock),
      )
      .zioValue
  }
}
