package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.repository.{OrganizationManagementRepository, UserDetailsRepository}
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.utils.*
import io.mesazon.gateway.utils.given
import io.mesazon.gateway.validation.domain.*
import io.mesazon.gateway.validation.service.*
import io.mesazon.testkit.base.ZWordSpecBase
import io.scalaland.chimney.dsl.*
import zio.*

import java.time.Instant
import java.time.temporal.ChronoUnit

class OrganizationManagementServiceSpec
    extends ZWordSpecBase,
      OrganizationManagementSmithyArbitraries,
      RepositoryArbitraries {

  "OrganizationManagementService" when {
    "createOrganizationPost" should {
      "successfully create organization for user in completed onboard stage" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(organizationStage = OrganizationStage.DetailsProvided)

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(userDetailsRow.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          organizationManagementRepositoryMock.createOrganization
            .expects(
              authedUser.userID,
              organizationDetailsRow.name,
              organizationDetailsRow.slug,
              organizationDetailsRow.tagline,
              organizationDetailsRow.emails,
              organizationDetailsRow.phoneNumbers,
              organizationDetailsRow.organizationStage,
              organizationDetailsRow.addresses,
              organizationDetailsRow.companyRegistrationNumber,
              organizationDetailsRow.taxID,
            )
            .returningZIO(organizationDetailsRow)
            .once(),
          emailClientMock.sendOrganizationCreatedEmail
            .expects(userDetailsRow.email, organizationDetailsRow.name)
            .returningZIOUnit
            .once(),
        )

        val createOrganizationPostRequestSmithy =
          organizationDetailsRow.transformInto[smithy.CreateOrganizationPostRequest]

        val organizationManagementService = buildOrganizationManagementService

        val createOrganizationPostResponse =
          organizationManagementService.createOrganizationPost(createOrganizationPostRequestSmithy).zioValue

        createOrganizationPostResponse shouldBe smithy.CreateOrganizationPostResponse(
          organizationDetailsRow.organizationID.value
        )
      }

      "successfully create organization for user even if email client fails" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val onboardStage   = Random.shuffle(OnboardStage.completedStages).zioValue.head
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID, onboardStage = onboardStage)
        val organizationDetailsRow = arbitrarySample[OrganizationDetailsRow]
          .copy(organizationStage = OrganizationStage.DetailsProvided)

        val sendOrganizationCreatedEmailCounter = counterRef.zioValue

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(Some(userDetailsRow))
            .once(),
          organizationManagementRepositoryMock.createOrganization
            .expects(
              authedUser.userID,
              organizationDetailsRow.name,
              organizationDetailsRow.slug,
              organizationDetailsRow.tagline,
              organizationDetailsRow.emails,
              organizationDetailsRow.phoneNumbers,
              organizationDetailsRow.organizationStage,
              organizationDetailsRow.addresses,
              organizationDetailsRow.companyRegistrationNumber,
              organizationDetailsRow.taxID,
            )
            .returningZIO(organizationDetailsRow)
            .once(),
          emailClientMock.sendOrganizationCreatedEmail
            .expects(userDetailsRow.email, organizationDetailsRow.name)
            .returns(
              sendOrganizationCreatedEmailCounter.incrementAndGet *> ZIO.fail(
                ServiceError.InternalServerError.UnexpectedError("Email service error")
              )
            )
            .once(),
        )

        val createOrganizationPostRequestSmithy =
          organizationDetailsRow.transformInto[smithy.CreateOrganizationPostRequest]

        val organizationManagementService = buildOrganizationManagementService

        val createOrganizationPostResponse =
          organizationManagementService.createOrganizationPost(createOrganizationPostRequestSmithy).zioValue

        createOrganizationPostResponse shouldBe smithy.CreateOrganizationPostResponse(
          organizationDetailsRow.organizationID.value
        )

        sendOrganizationCreatedEmailCounter.get.zioValue shouldBe organizationManagementConfig.sendOrganizationCreatedEmailMaxRetries + 1
      }

      "fail with UnexpectedError if user is not found" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        inSequence(
          (() => authStateMock.get).expects().returningZIO(authedUser).once(),
          userDetailsRepositoryMock.getUserDetails
            .expects(authedUser.userID)
            .returningZIO(None)
            .once(),
        )

        val createOrganizationPostRequestSmithy = arbitrarySample[smithy.CreateOrganizationPostRequest]

        val organizationManagementService = buildOrganizationManagementService

        val serviceError =
          organizationManagementService.createOrganizationPost(createOrganizationPostRequestSmithy).zioError

        serviceError shouldBe ServiceError.InternalServerError.UnexpectedError(
          s"User details not found for userID: [${authedUser.userID}]"
        )
      }
    }
  }

  trait TestContext {
    val instantNow = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    val organizationManagementConfig = OrganizationManagementConfig(
      sendOrganizationCreatedEmailMaxRetries = 5,
      sendOrganizationCreatedEmailRetryDelay = 1.millis,
    )

    val phoneNumberValidatorConfig = PhoneNumberValidatorConfig(
      supportedPhoneRegions = Set("GB", "CY")
    )

    val authStateMock                        = mock[AuthState]
    val userDetailsRepositoryMock            = mock[UserDetailsRepository]
    val organizationManagementRepositoryMock = mock[OrganizationManagementRepository]
    val emailClientMock                      = mock[EmailClient]

    def buildOrganizationManagementService: smithy.OrganizationManagementService[ServiceTask] =
      ZIO
        .service[smithy.OrganizationManagementService[ServiceTask]]
        .provide(
          OrganizationManagementService.local,
          PhoneNumberUtil.live,
          OrganizationManagementRequestValidator.live,
          EmailValidator.live,
          AddressValidator.live,
          ZLayer.succeed(organizationManagementConfig),
          ZLayer.succeed(phoneNumberValidatorConfig),
          PhoneNumberDomainValidator.live,
          ZLayer.succeed(userDetailsRepositoryMock),
          ZLayer.succeed(authStateMock),
          ZLayer.succeed(organizationManagementRepositoryMock),
          ZLayer.succeed(emailClientMock),
        )
        .zioValue
  }
}
