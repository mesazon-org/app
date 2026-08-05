package io.mesazon.gateway.tapir

import io.circe.syntax.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.*
import sttp.apispec.openapi.Info
import sttp.apispec.openapi.circe.*
import sttp.capabilities.zio.*
import sttp.model.StatusCode
import sttp.tapir.CodecFormat
import sttp.tapir.codec.iron.given
import sttp.tapir.docs.openapi.*
import sttp.tapir.ztapir.*
import zio.*

object FileServiceEndpoints {

  lazy val smithy4sDocsID = new smithy4s.HasId {
    override def id: smithy4s.ShapeId = smithy4s.ShapeId("io.mesazon.gateway.smithy", "FileService")
  }

  inline private val FileNameHeader        = "X-File-Name"
  inline private val CatalogueItemIDHeader = "X-Catalogue-Item-ID"

  private val uploadOrganizationLogoPostEndpoint =
    securedEndpoint.post
      .in("upload" / "organization" / "logo")
      .in(header[ImageOriginalFileName](FileNameHeader))
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
      .description(requiredOrganizationRolesDescription(OrganizationUserRole.adminRoles))

  private val uploadCatalogueItemImagePostEndpoint =
    securedEndpoint.post
      .in("upload" / "catalogue-item" / "image")
      .in(header[CatalogueItemID](CatalogueItemIDHeader))
      .in(header[ImageOriginalFileName](FileNameHeader))
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
      .description(requiredOrganizationRolesDescription(OrganizationUserRole.adminRoles))

  private val docsEndpoint =
    endpoint.get
      .in("docs" / "specs" / s"${smithy4sDocsID.id.namespace}.${smithy4sDocsID.id.name}.json")
      .out(jsonBodyStringRaw)

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
              organizationUserRolesAllowedOpt = Some(OrganizationUserRole.adminRoles),
            )
            .as(organizationID)
        }
          .serverLogic(organizationID => {
            case (organizationLogoImageOriginalFileName, organizationLogoImageByteStream) =>
              fileService
                .uploadOrganizationLogo(
                  organizationID,
                  organizationLogoImageOriginalFileName,
                  organizationLogoImageByteStream,
                )
          }),
        uploadCatalogueItemImagePostEndpoint.zServerSecurityLogic { case (accessToken, organizationID) =>
          authorizationService
            .auth(
              accessToken = accessToken,
              requiresCompletedOnboardStage = true,
              organizationIDOpt = Some(organizationID),
              organizationUserRolesAllowedOpt = Some(OrganizationUserRole.adminRoles),
            )
            .as(organizationID)
        }
          .serverLogic(organizationID => {
            case (catalogueItemID, catalogueItemImageOriginalFileName, catalogueItemImageByteStream) =>
              fileService.uploadCatalogueItemImage(
                organizationID,
                catalogueItemID,
                catalogueItemImageOriginalFileName,
                catalogueItemImageByteStream,
              )
          }),
      )
      openApiDocsOpt = Option.when(enableDocs)(
        OpenAPIDocsInterpreter()
          .toOpenAPI(
            List(uploadOrganizationLogoPostEndpoint, uploadCatalogueItemImagePostEndpoint),
            Info(
              title = "FileService",
              version = "1.0",
              description = Some("**Required Onboard Stage:** COMPLETED"),
            ),
          )
          .asJson
          .deepDropNullValues
          .spaces2
      )
      docsEndpointOpt: Option[ZServerEndpoint[Any, Any]] = openApiDocsOpt.map(openApiDocs =>
        docsEndpoint.zServerLogic(_ => ZIO.succeed(openApiDocs))
      )
    } yield (streamEndpoints, docsEndpointOpt)
}
