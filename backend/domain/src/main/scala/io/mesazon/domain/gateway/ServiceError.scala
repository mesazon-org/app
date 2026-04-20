package io.mesazon.domain.gateway

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

  // 500
  sealed abstract class InternalServerError(
      override val message: String,
      override val underlying: Option[Throwable] = None,
  ) extends ServiceError("InternalServer", message, underlying)

  // 503
  sealed abstract class ServiceUnavailableError(
      override val message: String,
      override val underlying: Option[Throwable] = None,
  ) extends ServiceError("Unavailable", message, underlying)

  object BadRequestError {
    case class InvalidFieldError(fieldName: String, errorMessage: String, invalidValues: Seq[String])

    object InvalidFieldError {
      def apply(fieldName: String, errorMessage: String, invalidValue: String): InvalidFieldError =
        InvalidFieldError(fieldName, errorMessage, Seq(invalidValue))
    }

    case class ValidationError(invalidFields: Seq[InvalidFieldError])
        extends BadRequestError(s"request validation error ${invalidFields.mkString("[", ",", "]")}")
  }

  object UnauthorizedError {
    case object TokenMissing extends UnauthorizedError("token is missing from request")

    case class FailedToVerifyJwt(error: String, throwable: Option[Throwable] = None)
        extends UnauthorizedError(error, throwable)

    case class OtpValidationError(error: String) extends UnauthorizedError(error)

    case class FailedToVerifyPassword(error: String, throwable: Option[Throwable] = None)
        extends UnauthorizedError(error, throwable)

    case class FailedOnboardStage(
        onboardStageUser: OnboardStage,
        onboardStagesAllowed: List[OnboardStage],
    ) extends UnauthorizedError(
          s"Failed onboard stage user [$onboardStageUser], allowed: [$onboardStagesAllowed]",
          None,
        )

    case class TokenFailedAuthorization(throwable: Throwable)
        extends UnauthorizedError("token failed authorization", Some(throwable))
  }

  object ConflictError {
    case class UserAlreadyExists(userID: UserID, email: Email)
        extends ConflictError(s"user with id [$userID] and email [$email] already exists")
  }

  object InternalServerError {
    case class UserNotFoundError(error: String) extends InternalServerError(error)

    case class DatabaseError(error: String, throwable: Throwable) extends InternalServerError(error, Some(throwable))

    case class UnexpectedError(error: String, throwable: Option[Throwable] = None)
        extends InternalServerError(error, throwable)
  }

  object ServiceUnavailableError {
    case class DatabaseUnavailableError(throwable: Throwable)
        extends ServiceUnavailableError("database is currently unavailable", Some(throwable))
  }
}
