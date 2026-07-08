package io.mesazon.gateway.tapir

import io.github.iltotore.iron.constraint.all.Trimmed
import io.mesazon.domain.gateway.TapirServerError
import io.mesazon.gateway.json.{tapirServerErrorSchemas, given}
import sttp.apispec.openapi.Info
import sttp.capabilities.zio.ZioStreams
import sttp.model.StatusCode
import sttp.monad.MonadError
import sttp.tapir.codec.iron.ValidatorForPredicate
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.interceptor.DecodeFailureContext
import sttp.tapir.server.interceptor.decodefailure.{DecodeFailureHandler, DefaultDecodeFailureHandler}
import sttp.tapir.server.model.ValuedEndpointOutput
import sttp.tapir.ztapir.*
import sttp.tapir.{EndpointOutput, ValidationError, Validator}
import zio.*

private[tapir] given ValidatorForPredicate[String, Trimmed] = new ValidatorForPredicate[String, Trimmed] {
  override def validator: Validator[String] =
    Validator.pattern[String]("""^$|^\S(?:.*\S)?$""")

  override def makeErrors(value: String, errorMessage: String): List[ValidationError[?]] =
    validator.apply(value).map(_.copy(customMessage = Some(errorMessage)))
}

private[tapir] val TapirDocsPath: List[String] =
  List("tapir-docs")

private[tapir] val TapirOpenApiYamlName: String =
  "openapi.yaml"

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

private[tapir] val apiInfo: Info =
  Info(
    title = "Gateway Tapir API",
    version = "1.0",
    description = Some(
      """# Global Requirements
        |**Required Onboard Stage:** **COMPLETED**
        |
        |All endpoints in this service require the user to have finished
        |the onboarding flow (Phone & Email Verified).""".stripMargin
    ),
  )

type TapirTask[A] = IO[TapirServerError, A]

type TapirEndpoints =
  (stream: List[ZServerEndpoint[Any, ZioStreams]], docsOpt: Option[List[ZServerEndpoint[Any, ZioStreams]]])
