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

  // 409
  sealed abstract class ConflictError(
      override val message: String,
      override val underlying: Option[Throwable] = None,
  ) extends ServiceError("ConflictError", message, underlying)

  object BadRequestError {
    type InvalidFieldError = (fieldName: String, errorMessage: String)

    final case class NoEffect(error: String) extends BadRequestError(s"request has no effect error: [$error]")

    final case class FormValidationError(invalidFields: Seq[InvalidFieldError])
        extends BadRequestError(s"request validation error ${invalidFields.mkString("[", ",", "]")}")
  }

  object UnauthorizedError {
    case object TokenMissing extends UnauthorizedError("token is missing from request")

    final case class TokenFailedAuthorization(throwable: Throwable)
        extends UnauthorizedError("token failed authorization", Some(throwable))
  }

  object ConflictError {
    final case class UserAlreadyExists(userID: UserID, email: Email)
        extends ConflictError(s"user with id [$userID] and email [$email] already exists")
  }
}
