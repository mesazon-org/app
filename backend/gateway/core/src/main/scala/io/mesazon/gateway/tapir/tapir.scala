package io.mesazon.gateway.tapir

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.constraint.all.Trimmed
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.json.{tapirServerErrorSchemas, given}
import io.mesazon.gateway.service.AuthorizationService
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.codec.iron.ValidatorForPredicate
import sttp.tapir.codec.iron.given
import sttp.tapir.json.jsoniter.jsonBody
import sttp.tapir.server.http4s.Http4sServerOptions
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.interceptor.decodefailure.*
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.ztapir.*
import sttp.tapir.{Codec, CodecFormat, EndpointIO, EndpointOutput, ValidationError, Validator}
import zio.*
import zio.interop.catz.*

import java.nio.charset.StandardCharsets

type TapirTask[A] = IO[TapirServerError, A]

type TapirEndpoints =
  (stream: List[ZServerEndpoint[Any, ZioStreams]], docsOpt: Option[ZServerEndpoint[Any, Any]])

val tapirServerOptions: Http4sServerOptions[Task] =
  Http4sServerOptions
    .customiseInterceptors[Task]
    .decodeFailureHandler(decodeFailureHandler)
    .options

private[tapir] given JsonValueCodec[String] = JsonCodecMaker.make[String]

private[tapir] given ValidatorForPredicate[String, Trimmed] = new ValidatorForPredicate[String, Trimmed] {
  override def validator: Validator[String] =
    Validator.pattern[String]("""^$|^\S(?:.*\S)?$""")

  override def makeErrors(value: String, errorMessage: String): List[ValidationError[?]] =
    validator.apply(value).map(_.copy(customMessage = Some(errorMessage)))
}

private[tapir] def tapirServerErrorForStatus(status: StatusCode): TapirServerError =
  TapirServerError.values.find(error => statusCodeFor(error) == status).getOrElse(TapirServerError.BadRequestError)

private[tapir] val staticBodyDecodeFailureHandler: DefaultDecodeFailureHandler[Task] =
  DefaultDecodeFailureHandler[Task].copy(response =
    (status, headerList, _) =>
      ValuedEndpointOutput(jsonBody[TapirServerError], tapirServerErrorForStatus(status))
        .prepend(statusCode.and(headers), (status, headerList))
  )

private[tapir] val decodeFailureHandler: DecodeFailureHandler[Task] =
  new DecodeFailureHandler[Task] {
    override def apply(ctx: DecodeFailureContext)(implicit
        monad: MonadError[Task]
    ): Task[Option[ValuedEndpointOutput[?]]] =
      staticBodyDecodeFailureHandler(ctx).flatMap {
        case Some(valuedEndpointOutput) =>
          ZIO
            .logWarning(s"Request input decoding failed: ${staticBodyDecodeFailureHandler.failureMessage(ctx)}")
            .as(Some(valuedEndpointOutput))
        case None =>
          ZIO.none
      }
  }

private[tapir] def statusCodeFor(tapirServerError: TapirServerError): StatusCode =
  tapirServerError match {
    case TapirServerError.BadRequestError         => StatusCode.BadRequest
    case TapirServerError.UnauthorizedError       => StatusCode.Unauthorized
    case TapirServerError.ForbiddenError          => StatusCode.Forbidden
    case TapirServerError.NotFoundError           => StatusCode.NotFound
    case TapirServerError.InternalServerError     => StatusCode.InternalServerError
    case TapirServerError.ServiceUnavailableError => StatusCode.ServiceUnavailable
  }

private[tapir] def tapirServerErrorOut(
    tapirServerErrors: NonEmptyChunk[TapirServerError]
): EndpointOutput.OneOf[TapirServerError, TapirServerError] = {
  val variants =
    tapirServerErrors.map(tapirServerError =>
      oneOfVariantExactMatcher(
        statusCodeFor(tapirServerError),
        jsonBody[TapirServerError].schema(tapirServerErrorSchemas(tapirServerError)),
      )(tapirServerError)
    )

  // `oneOfDefaultVariant` matches every value, so it must come LAST — otherwise it would shadow the exact-match
  // variants and every error would be rendered as 500.
  val default = oneOfDefaultVariant(
    statusCode(StatusCode.InternalServerError)
      .and(jsonBody[TapirServerError].schema(tapirServerErrorSchemas(TapirServerError.InternalServerError)))
  )

  val allVariants = variants :+ default
  oneOf[TapirServerError](allVariants.head, allVariants.tail*)
}

// The OpenAPI document is already-serialized JSON. `jsonBody[String]` would re-encode it as a JSON string literal
// (quoted and escaped), which Swagger UI can't parse. Serve the raw string with an `application/json` content type.
private[tapir] val jsonBodyStringRaw: EndpointIO.Body[String, String] =
  stringBodyAnyFormat(Codec.string.format(CodecFormat.Json()), StandardCharsets.UTF_8)

/** Swagger marker documenting the organization roles an endpoint requires, mirroring the smithy
  * `/// **Required Organization User Roles:** [...]` doc comment. Derives the role names from the same
  * `OrganizationUserRole` list the endpoint's security logic enforces, so the docs cannot drift.
  */
private[tapir] def requiredOrganizationRolesDescription(roles: List[OrganizationUserRole]): String =
  roles.map(role => s"`${role.toString.toUpperCase}`").mkString("**Required Organization User Roles:** [", ", ", "]")

private[tapir] val securedEndpoint =
  endpoint
    .securityIn(auth.bearer[AccessToken]())
    .securityIn(header[OrganizationID](AuthorizationService.OrganizationIDHeader.toString))
