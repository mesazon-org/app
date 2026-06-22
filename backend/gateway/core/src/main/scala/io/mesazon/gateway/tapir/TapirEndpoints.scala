package io.mesazon.gateway.tapir

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.json.given
import io.mesazon.gateway.service.FileService
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.CodecFormat
import sttp.tapir.codec.iron.given
import sttp.tapir.json.jsoniter.*
import sttp.tapir.swagger.SwaggerUIOptions
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir.*
import zio.*

object TapirEndpoints {

  private val TapirDocsPath: List[String] =
    List("tapir-docs")

  private val TapirOpenApiYamlName: String =
    "openapi.yaml"

  private val uploadOrganizationLogoPostEndpoint =
    endpoint.post
      .in("upload" / "organization" / "logo" / path[OrganizationID]("organizationID"))
      .in(header[OrganizationLogoFileName]("X-File-Name"))
      .in(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .out(statusCode(StatusCode.Ok))
      .errorOut(statusCode and jsonBody[TapirServerError])

  def allRoutesAndDocsEndpoints(enableDocs: Boolean) =
    for {
      fileService <- ZIO.service[FileService[TapirTask]]

      streamEndpoints: List[ZServerEndpoint[Any, ZioStreams]] = List(
        uploadOrganizationLogoPostEndpoint.zServerLogic(fileService.uploadOrganizationLogo)
      )

      swaggerEndpointsOpt: Option[List[ZServerEndpoint[Any, ZioStreams]]] = Option.when(enableDocs)(
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

    } yield (streamEndpoints, swaggerEndpointsOpt)
}
