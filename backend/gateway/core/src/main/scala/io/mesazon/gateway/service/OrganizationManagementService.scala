package io.mesazon.gateway.service

import io.mesazon.gateway.repository.*
import io.mesazon.gateway.state.AuthState
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

object OrganizationManagementService {

  private final class OrganizationManagementServiceImpl(
      authState: AuthState,
      organizationManagementRepository: OrganizationManagementRepository,
  ) extends smithy.OrganizationManagementService[ServiceTask] {

    /** **Required Onboard Stage:** **COMPLETED**
      *
      * HTTP POST /create/organization
      */
    override def createOrganizationPost(
        request: smithy.CreateOrganizationPostRequest
    ): ServiceTask[smithy.CreateOrganizationPostResponse] = for {
      authedUser <- authState.get
      _          <- organizationManagementRepository.createOrganization(
        authedUser.userID,
        ???,
        ???,
        ???,
        ???,
        ???,
        ???,
        ???,
        ???,
        ???,
        ???,
      )
    } yield ???

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
          request: smithy.CreateOrganizationPostRequest
      ): Task[smithy.CreateOrganizationPostResponse] =
        HttpErrorHandler
          .errorResponseHandler(service.createOrganizationPost(request))
    }

  val local = ZLayer
    .derive[OrganizationManagementServiceImpl]
    .project[smithy.OrganizationManagementService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
