package io.mesazon.gateway.tapir

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.FileService
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

  private val uploadOrganizationLogoPostEndpoint =
    endpoint.post
      .in("upload" / "organization" / "logo" / path[OrganizationID]("organizationID"))
      .in(header[OrganizationLogoOriginalFileName]("X-File-Name"))
      .in(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .out(statusCode(StatusCode.Ok))
      .errorOut(
        tapirServerErrorOut(
          NonEmptyChunk(
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
  ): ZIO[FileService[TapirTask], Nothing, TapirEndpoints] =
    for {
      fileService <- ZIO.service[FileService[TapirTask]]
      streamEndpoints: List[ZServerEndpoint[Any, ZioStreams]] = List(
        uploadOrganizationLogoPostEndpoint.zServerLogic(fileService.uploadOrganizationLogo)
      )
      docsEndpointsOpt: Option[List[ZServerEndpoint[Any, ZioStreams]]] = Option.when(enableDocs)(
        SwaggerInterpreter(
          swaggerUIOptions = SwaggerUIOptions.default.copy(
            pathPrefix = TapirDocsPath,
            yamlName = TapirOpenApiYamlName,
          )
        ).fromServerEndpoints(
          streamEndpoints,
          "Gateway Tapir API",
          "1.0",
        )
      )
    } yield (streamEndpoints, docsEndpointsOpt)
}
