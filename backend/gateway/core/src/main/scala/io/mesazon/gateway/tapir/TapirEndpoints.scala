package io.mesazon.gateway.tapir

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.json.given
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.tapir.CodecFormat
import sttp.tapir.codec.iron.given
import sttp.tapir.json.jsoniter.*
import sttp.tapir.ztapir.*

object TapirEndpoints {

//  val uploadOrganizationLogoPostEndpoint =
  val _ =
    endpoint
      .name("Upload Organization Logo")
      .summary("Stream-upload a logo image for an organization")
      .description(
        "Accepts a raw `application/octet-stream` body and stores it as the logo for the given " +
          "`organizationID`. Requires a valid Bearer JWT access token issued after a completed " +
          "onboard stage. Maximum body size: 2 MB (enforced by the server-level EntityLimiter)."
      )
      .post
      .securityIn(auth.bearer[String]())
      .in("upload" / "organization" / "logo" / path[OrganizationID]("organizationID"))
      .in(header[String]("X-File-Name").description("The original name of the file being uploaded"))
      .in(streamBinaryBody(ZioStreams)(CodecFormat.OctetStream()))
      .out(statusCode(StatusCode.Ok))
      .errorOut(statusCode.and(jsonBody[TapirServerError]))
      .tag("File")
}
