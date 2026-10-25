package io.mesazon.gateway.tapir

import io.circe.syntax.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.json.tapir.given
import io.mesazon.gateway.service.*
import sttp.apispec.openapi.Info
import sttp.apispec.openapi.circe.*
import sttp.capabilities.zio.*
import sttp.model.StatusCode
import sttp.tapir.CodecFormat
import sttp.tapir.codec.iron.given
import sttp.tapir.docs.openapi.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.ztapir.*
import zio.*

object CatalogueFileServiceEndpoints {

  private inline val FileNameHeader          = "X-File-Name"
  private inline val CatalogueItemIDHeader   = "X-Catalogue-Item-ID"
  private inline val ProcessingMessageHeader = "X-Processing-Message"

  private val catalogueFileUploadImageItemPostEndpoint =
    securedEndpoint.post
      .in("catalogue" / "file" / "upload" / "image" / "item")
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

  private val catalogueFileAiExtractPostEndpoint =
    securedEndpoint.post
      .in("catalogue" / "file" / "ai" / "extract")
      .in(header[CatalogueFileExtractFileName](FileNameHeader))
      .in(
        header[Option[String]](ProcessingMessageHeader)
          .description("Optional processing guidance, up to 2,000 characters after trimming. Blank means no guidance.")
      )
      .in(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .out(jsonBody[CatalogueFileAiExtractPostResponse])
      .errorOut(
        tapirServerErrorOut(
          NonEmptyChunk(
            TapirServerError.BadRequestError,
            TapirServerError.UnauthorizedError,
            TapirServerError.ForbiddenError,
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
  ): ZIO[CatalogueFileService[TapirTask] & AuthorizationService[TapirTask], Nothing, TapirEndpoints] =
    for {
      catalogueFileService <- ZIO.service[CatalogueFileService[TapirTask]]
      authorizationService <- ZIO.service[AuthorizationService[TapirTask]]
      streamEndpoints: List[ZServerEndpoint[Any, ZioStreams]] = List(
        catalogueFileUploadImageItemPostEndpoint.zServerSecurityLogic { case (accessToken, organizationID) =>
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
              catalogueFileService.uploadImageItem(
                organizationID,
                catalogueItemID,
                catalogueItemImageOriginalFileName,
                catalogueItemImageByteStream,
              )
          }),
        catalogueFileAiExtractPostEndpoint.zServerSecurityLogic { case (accessToken, organizationID) =>
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
            case (catalogueFileExtractFileName, catalogueFileProcessingMessageRawOpt, catalogueFileExtractByteStream) =>
              for {
                catalogueFileProcessingMessageOpt <- ZIO.foreach(
                  catalogueFileProcessingMessageRawOpt.map(_.trim).filter(_.nonEmpty)
                )(catalogueFileProcessingMessageRaw =>
                  ZIO
                    .fromEither(CatalogueFileProcessingMessage.either(catalogueFileProcessingMessageRaw))
                    .mapError(_ => TapirServerError.BadRequestError)
                )
                response <- catalogueFileService.aiExtract(
                  organizationID,
                  catalogueFileExtractFileName,
                  catalogueFileProcessingMessageOpt,
                  catalogueFileExtractByteStream,
                )
              } yield response
          }),
      )
      docsEndpointOpt: Option[ZServerEndpoint[Any, Any]] = Option.when(enableDocs)(
        docsEndpoint.zServerLogic(_ => ZIO.succeed(openApiDocs))
      )
    } yield (streamEndpoints, docsEndpointOpt)

  lazy val smithy4sDocsID = new smithy4s.HasId {
    override def id: smithy4s.ShapeId = smithy4s.ShapeId("io.mesazon.gateway.smithy", "CatalogueFileService")
  }

  lazy val openApiDocs: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(
        List(catalogueFileUploadImageItemPostEndpoint, catalogueFileAiExtractPostEndpoint),
        Info(
          title = "CatalogueFileService",
          version = "1.0",
          description = Some("**Required Onboard Stage:** COMPLETED"),
        ),
      )
      .asJson
      .deepDropNullValues
      .spaces2
}
