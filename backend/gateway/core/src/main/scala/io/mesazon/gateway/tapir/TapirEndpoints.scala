package io.mesazon.gateway.tapir

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.*
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.CodecFormat
import sttp.tapir.codec.iron.given
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*
import zio.interop.catz.*

object TapirEndpoints {

  private val UploadOrganizationLogoRolesAllowed = List(OrganizationUserRole.Owner, OrganizationUserRole.Admin)

  private val securedEndpoint =
    endpoint
      .securityIn(auth.bearer[AccessToken]())
      .securityIn(header[OrganizationID](AuthorizationService.OrganizationIDHeader.toString))

  private val uploadOrganizationLogoPostEndpoint =
    securedEndpoint.post
      .in("upload" / "organization" / "logo")
      .in(header[OrganizationLogoOriginalFileName]("X-File-Name"))
      .in(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .out(statusCode(StatusCode.Ok))
      .errorOut(
        tapirServerErrorOut(
          NonEmptyChunk(
            TapirServerError.UnauthorizedError,
            TapirServerError.ForbiddenError,
            TapirServerError.BadRequestError,
            TapirServerError.InternalServerError,
          )
        )
      )

  val serverOptions: Http4sServerOptions[Task] =
    Http4sServerOptions
      .customiseInterceptors[Task]
      .decodeFailureHandler(decodeFailureHandler)
      .options

  def allRoutesAndDocsEndpoints(
      enableDocs: Boolean
  ): ZIO[FileService[TapirTask] & AuthorizationService[TapirTask], Nothing, TapirEndpoints] =
    for {
      fileService          <- ZIO.service[FileService[TapirTask]]
      authorizationService <- ZIO.service[AuthorizationService[TapirTask]]
      streamEndpoints: List[ZServerEndpoint[Any, ZioStreams]] = List(
        uploadOrganizationLogoPostEndpoint.zServerSecurityLogic { case (accessToken, organizationID) =>
          authorizationService
            .auth(
              accessToken = accessToken,
              requiresCompletedOnboardStage = true,
              organizationIDOpt = Some(organizationID),
              organizationUserRolesAllowedOpt = Some(UploadOrganizationLogoRolesAllowed),
            )
            .as(organizationID)
        }
          .serverLogic(organizationID => { case (organizationLogoOriginalFileName, organizationLogoFile) =>
            fileService.uploadOrganizationLogo(organizationID, organizationLogoOriginalFileName, organizationLogoFile)
          })
      )
      docsEndpointsOpt: Option[List[ZServerEndpoint[Any, ZioStreams]]] = Option.when(enableDocs)(
        SwaggerInterpreter(
          swaggerUIOptions = SwaggerUIOptions.default.copy(
            pathPrefix = TapirDocsPath,
            yamlName = TapirOpenApiYamlName,
          )
        ).fromServerEndpoints(
          streamEndpoints,
          apiInfo,
        )
      )
    } yield (streamEndpoints, docsEndpointsOpt)
}
