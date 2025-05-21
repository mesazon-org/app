package io.rikkos.domain

sealed abstract class ServiceError(
    val errorType: String,
    val message: String,
    val underlying: Option[Throwable] = None,
) extends Exception(s"($errorType) $message", underlying.orNull)

object ServiceError {

  // 400
  sealed abstract class BadRequestError(
      override val message: String,
      override val underlying: Option[Throwable] = None,
  ) extends ServiceError("BadRequestError", message, underlying)

  // 401
  sealed abstract class UnauthorizedError(
      override val message: String,
      override val underlying: Option[Throwable] = None,
  ) extends ServiceError("UnauthorizedError", message, underlying)

  object BadRequestError {
    final case class RequestValidationError(errors: Seq[String])
        extends BadRequestError(s"request validation error [${errors.mkString(", ")}]")
  }

  object UnauthorizedError {
    case object TokenMissing extends UnauthorizedError("token is missing from request")

    final case class TokenFailedAuthorization(throwable: Throwable)
        extends UnauthorizedError("token failed authorization", Some(throwable))
  }
}
