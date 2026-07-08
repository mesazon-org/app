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

  // 403
  sealed abstract class ForbiddenError(
      override val message: String,
      override val underlying: Option[Throwable] = None,
  ) extends ServiceError("ForbiddenError", message, underlying)

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

    case class HeaderMissingError(headerName: String)
        extends BadRequestError(s"Required header [$headerName] is missing")

    case class ValidationError(invalidFields: Seq[InvalidFieldError])
        extends BadRequestError(s"request validation error ${invalidFields.mkString("[", ",", "]")}")

    case class OtpVerifyError(error: String) extends BadRequestError(error)
  }

  object UnauthorizedError {
    case class AuthHeaderMissingError(headerName: String)
        extends UnauthorizedError(s"Authorization header [$headerName] is missing")

    case class TokenFailedAuthorization(error: String, throwable: Option[Throwable] = None)
        extends UnauthorizedError(error, throwable)

    case object AuthenticationEmailNotFound extends UnauthorizedError("Authentication email not found")

    case object AuthenticationInvalidCredentials extends UnauthorizedError("invalid credentials")

    case class FailedToVerifyJwt(error: String, throwable: Option[Throwable] = None)
        extends UnauthorizedError(error, throwable)

    case class OtpExpiredError(error: String) extends UnauthorizedError(error)

    case class AuthenticationTooManySignInAttempts(
        userID: UserID,
        actionAttemptType: ActionAttemptType,
        blockDurationSeconds: Long,
    ) extends UnauthorizedError(
          s"too many requests for user [$userID] and action attempt type [$actionAttemptType], block for [$blockDurationSeconds] seconds"
        )
  }

  object ForbiddenError {

    case class InvalidOrganizationRole(
        organizationID: OrganizationID,
        userID: UserID,
        organizationUserRole: UserRole,
        organizationRolesAllowed: List[UserRole],
    ) extends ForbiddenError(
          s"Invalid organization role for organization id: [$organizationID] and user id: [$userID], user role: [$organizationUserRole], allowed roles: [$organizationRolesAllowed]",
          None,
        )

    case class InvalidOnboardStage(
        userID: UserID,
        onboardStageUser: OnboardStage,
        onboardStagesAllowed: List[OnboardStage],
    ) extends ForbiddenError(
          s"Invalid onboard stage user id: [$userID] with onboard stage: [$onboardStageUser], allowed: [$onboardStagesAllowed]",
          None,
        )
  }

  object InternalServerError {
    case class RepositoryError(error: String, throwable: Throwable) extends InternalServerError(error, Some(throwable))

    case class PasswordServiceError(error: String, throwable: Option[Throwable] = None)
        extends InternalServerError(error, throwable)

    case class JwtServiceError(error: String, throwable: Option[Throwable] = None)
        extends InternalServerError(error, throwable)

    case class AuthenticationError(error: String, throwable: Option[Throwable] = None)
        extends InternalServerError(error, throwable)

    case class UnexpectedError(error: String, throwable: Option[Throwable] = None)
        extends InternalServerError(error, throwable)
  }

  object ServiceUnavailableError {
    case class DatabaseUnavailableError(throwable: Throwable)
        extends ServiceUnavailableError("database is currently unavailable", Some(throwable))

    case class S3UnavailableError(bucket: String, throwable: Throwable)
        extends ServiceUnavailableError(s"S3 bucket connection [$bucket] is not available", Some(throwable))
  }
}
