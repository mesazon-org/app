package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import zio.*
import zio.stream.*

trait FileService[F[_]] {
  def uploadOrganizationLogo(
      userID: UserID,
      organizationID: OrganizationID,
      organizationLogoFileName: OrganizationLogoFileName,
      organizationLogoFile: ZStream[Any, Throwable, Byte],
  ): F[Unit]
}

object FileService {

//  val buildRoutes =
//    for {
//      fileService          <- ZIO.service[FileService[ServiceTask]]
//      authState            <- ZIO.service[AuthState]
//      authorizationService <- ZIO.service[AuthorizationService[ServiceTask]]
//    } yield {
//      val serverEndpoint: ZServerEndpoint[Any, ZioStreams] =
//        TapirEndpoints.uploadOrganizationLogoPostEndpoint.zServerSecurityLogic { accessTokenRaw =>
//          TapirErrorHandler.handleError(
//            authorizationService.auth(
//              request = Request[Task]().putHeaders(
//                Authorization(Credentials.Token(AuthScheme.Bearer, accessTokenRaw))
//              ),
//              requiresCompletedOnboardStage = true,
//            )
//          ) *> authState.get
//        }.serverLogic { authedUserAccess =>
//          { case (organizationID, bodyStream) =>
//            TapirErrorHandler.handleError(
//              fileService
//                .uploadOrganizationLogo(authedUserAccess.userID, organizationID, bodyStream)
//            )
//          }
//        }
//
//      ZHttp4sServerInterpreter().from(serverEndpoint).toRoutes
//    }

//  def swaggerRoutes: HttpRoutes[Task] = {
//    val swaggerEndpoints =
//      SwaggerInterpreter(
//        swaggerUIOptions = SwaggerUIOptions.default.copy(pathPrefix = List("file-docs"))
//      ).fromEndpoints[Task](
//        List(uploadOrganizationLogoEndpoint),
//        "File API",
//        "1.0",
//      )
//    Http4sServerInterpreter[Task]().toRoutes(swaggerEndpoints)
//  }

  private final class FileServiceImpl(
//      organizationManagementRepository: OrganizationManagementRepository
  ) extends FileService[ServiceTask] {

    override def uploadOrganizationLogo(
        userID: UserID,
        organizationID: OrganizationID,
        organizationLogoFileName: OrganizationLogoFileName,
        organizationLogoFile: ZStream[Any, Throwable, Byte],
    ): ServiceTask[Unit] = ???
  }

  def observed(service: FileService[ServiceTask]): FileService[ServiceTask] =
    new FileService[ServiceTask] {
      override def uploadOrganizationLogo(
          userID: UserID,
          organizationID: OrganizationID,
          organizationLogoFileName: OrganizationLogoFileName,
          organizationLogoFile: ZStream[Any, Throwable, Byte],
      ): ServiceTask[Unit] =
        service
          .uploadOrganizationLogo(userID, organizationID, organizationLogoFileName, organizationLogoFile)
    }

  val local = ZLayer
    .derive[FileServiceImpl]
    .project[FileService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
