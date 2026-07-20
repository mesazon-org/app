package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.EmailClient
import io.mesazon.gateway.config.OrganizationManagementConfig
import io.mesazon.gateway.repository.*
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.validation.service.OrganizationManagementRequestValidator
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object OrganizationManagementService {

  private final class OrganizationManagementServiceImpl(
      organizationManagementConfig: OrganizationManagementConfig,
      authState: AuthState,
      organizationManagementRepository: OrganizationManagementRepository,
      userDetailsRepository: UserDetailsRepository,
      emailClient: EmailClient,
      organizationManagementRequestValidator: OrganizationManagementRequestValidator,
  ) extends smithy.OrganizationManagementService[ServiceTask] {

    /** **Required Onboard Stage:** **COMPLETED**
      *
      * HTTP POST /create/organization
      */
    override def createOrganizationPost(
        createOrganizationPostRequestSmithy: smithy.CreateOrganizationPostRequest
    ): ServiceTask[smithy.CreateOrganizationPostResponse] = for {
      authedUser                    <- authState.get
      createOrganizationPostRequest <- organizationManagementRequestValidator.validatedCreateOrganizationPostRequest(
        createOrganizationPostRequestSmithy
      )
      userDetailsRow <- userDetailsRepository
        .getUserDetails(authedUser.userID)
        .someOrFail(
          ServiceError.InternalServerError.UnexpectedError(s"User details not found for userID: [${authedUser.userID}]")
        )
      organizationDetailsRow <- organizationManagementRepository
        .createOrganization(
          authedUser.userID,
          createOrganizationPostRequest.name,
          createOrganizationPostRequest.slug,
          createOrganizationPostRequest.tagline,
          createOrganizationPostRequest.emails,
          createOrganizationPostRequest.phoneNumbers,
          OrganizationStage.DetailsProvided,
          createOrganizationPostRequest.addressLine1,
          createOrganizationPostRequest.addressLine2,
          createOrganizationPostRequest.city,
          createOrganizationPostRequest.postalCode,
          createOrganizationPostRequest.country,
          createOrganizationPostRequest.companyRegistrationNumber,
          createOrganizationPostRequest.taxID,
        )
      _ <- emailClient
        .sendOrganizationCreatedEmail(
          userDetailsRow.email,
          organizationDetailsRow.name,
        )
        .retry(
          Schedule.recurs(organizationManagementConfig.sendOrganizationCreatedEmailMaxRetries) && Schedule
            .exponential(organizationManagementConfig.sendOrganizationCreatedEmailRetryDelay)
        )
        .catchAllCause(cause =>
          ZIO.logErrorCause(
            s"Failed to send organization created email for userID: [${authedUser.userID}]",
            cause,
          )
        )
    } yield smithy.CreateOrganizationPostResponse(organizationDetailsRow.organizationID.value)
  }

  def observed(
      service: smithy.OrganizationManagementService[ServiceTask]
  ): smithy.OrganizationManagementService[Task] =
    new smithy.OrganizationManagementService[Task] {

      /** **Required Onboard Stage:** **COMPLETED**
        *
        * HTTP POST /create/organization
        */
      override def createOrganizationPost(
          createOrganizationPostRequestSmithy: smithy.CreateOrganizationPostRequest
      ): Task[smithy.CreateOrganizationPostResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.createOrganizationPost(createOrganizationPostRequestSmithy))
    }

  val local = ZLayer
    .derive[OrganizationManagementServiceImpl]
    .project[smithy.OrganizationManagementService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
