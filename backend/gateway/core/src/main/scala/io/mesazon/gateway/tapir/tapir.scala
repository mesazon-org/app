package io.mesazon.gateway.tapir

import io.github.iltotore.iron.constraint.all.Trimmed
import io.mesazon.domain.gateway.TapirServerError
import io.mesazon.gateway.json.given
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

type TapirTask[A] = IO[TapirServerError, A]

type TapirEndpoints =
  (stream: List[ZServerEndpoint[Any, ZioStreams]], docsOpt: Option[List[ZServerEndpoint[Any, ZioStreams]]])

private given ValidatorForPredicate[String, Trimmed] = new ValidatorForPredicate[String, Trimmed] {
  override def validator: Validator[String] =
    Validator.pattern[String]("""^$|^\S(?:.*\S)?$""")

  override def makeErrors(value: String, errorMessage: String): List[ValidationError[?]] =
    validator.apply(value).map(_.copy(customMessage = Some(errorMessage)))
}

private val TapirDocsPath: List[String] =
  List("tapir-docs")

private val TapirOpenApiYamlName: String =
  "openapi.yaml"

private val staticBodyDecodeFailureHandler: DefaultDecodeFailureHandler[Task] =
  DefaultDecodeFailureHandler[Task].response { _ =>
    ValuedEndpointOutput(
      jsonBody[TapirServerError],
      TapirServerError.BadRequestError,
    )
  }

private val decodeFailureHandler: DecodeFailureHandler[Task] =
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

private def statusCodeFor(tapirServerError: TapirServerError): StatusCode =
  tapirServerError match {
    case TapirServerError.BadRequestError         => StatusCode.BadRequest
    case TapirServerError.UnauthorizedError       => StatusCode.Unauthorized
    case TapirServerError.NotFoundError           => StatusCode.NotFound
    case TapirServerError.InternalServerError     => StatusCode.InternalServerError
    case TapirServerError.ServiceUnavailableError => StatusCode.ServiceUnavailable
  }

private def tapirServerErrorOut(
    tapirServerErrors: NonEmptyChunk[TapirServerError]
): EndpointOutput.OneOf[TapirServerError, TapirServerError] = {
  val variants =
    tapirServerErrors.map(tapirServerError =>
      oneOfVariantExactMatcher(statusCodeFor(tapirServerError), jsonBody[TapirServerError])(tapirServerError)
    )

  val default = oneOfDefaultVariant(statusCode(StatusCode.InternalServerError).and(jsonBody[TapirServerError]))

  oneOf[TapirServerError](default, variants*)
}
