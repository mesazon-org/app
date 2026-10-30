package io.mesazon.gateway.tapir

import io.circe.syntax.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.json.tapir.given
import io.mesazon.gateway.service.*
import sttp.apispec.openapi.Info
import sttp.apispec.openapi.circe.*
import sttp.capabilities.zio.*
import sttp.tapir.CodecFormat
import sttp.tapir.codec.iron.given
import sttp.tapir.docs.openapi.*
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.ztapir.*
import zio.*

object CustomerBookFileServiceEndpoints {

  private inline val FileNameHeader          = "X-File-Name"
  private inline val ProcessingMessageHeader = "X-Processing-Message"

  private val customerBookFileAiExtractPostEndpoint =
    securedEndpoint.post
      .in("customer-book" / "file" / "ai" / "extract")
      .in(header[CustomerBookFileExtractFileName](FileNameHeader))
      .in(
        header[Option[String]](ProcessingMessageHeader)
          .description("Optional processing guidance, up to 2,000 characters after trimming. Blank means no guidance.")
      )
      .in(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .out(jsonBody[CustomerBookFileAiExtractPostResponse])
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
  ): ZIO[CustomerBookFileService[TapirTask] & AuthorizationService[TapirTask], Nothing, TapirEndpoints] =
    for {
      customerBookFileService <- ZIO.service[CustomerBookFileService[TapirTask]]
      authorizationService    <- ZIO.service[AuthorizationService[TapirTask]]
      streamEndpoints: List[ZServerEndpoint[Any, ZioStreams]] = List(
        customerBookFileAiExtractPostEndpoint.zServerSecurityLogic { case (accessToken, organizationID) =>
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
            case (
                  customerBookFileExtractFileName,
                  customerBookFileProcessingMessageRawOpt,
                  customerBookFileExtractByteStream,
                ) =>
              for {
                customerBookFileProcessingMessageOpt <- ZIO.foreach(
                  customerBookFileProcessingMessageRawOpt.map(_.trim).filter(_.nonEmpty)
                )(customerBookFileProcessingMessageRaw =>
                  ZIO
                    .fromEither(CustomerBookFileProcessingMessage.either(customerBookFileProcessingMessageRaw))
                    .mapError(_ => TapirServerError.BadRequestError)
                )
                response <- customerBookFileService.aiExtract(
                  organizationID,
                  customerBookFileExtractFileName,
                  customerBookFileProcessingMessageOpt,
                  customerBookFileExtractByteStream,
                )
              } yield response
          })
      )
      docsEndpointOpt: Option[ZServerEndpoint[Any, Any]] = Option.when(enableDocs)(
        docsEndpoint.zServerLogic(_ => ZIO.succeed(openApiDocs))
      )
    } yield (streamEndpoints, docsEndpointOpt)

  lazy val smithy4sDocsID = new smithy4s.HasId {
    override def id: smithy4s.ShapeId = smithy4s.ShapeId("io.mesazon.gateway.smithy", "CustomerBookFileService")
  }

  lazy val openApiDocs: String =
    OpenAPIDocsInterpreter()
      .toOpenAPI(
        List(customerBookFileAiExtractPostEndpoint),
        Info(
          title = "CustomerBookFileService",
          version = "1.0",
          description = Some("**Required Onboard Stage:** COMPLETED"),
        ),
      )
      .asJson
      .deepDropNullValues
      .spaces2
}
