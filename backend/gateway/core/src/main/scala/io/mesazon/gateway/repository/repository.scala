package io.mesazon.gateway.repository

import io.mesazon.domain.gateway.ServiceError
import org.postgresql.util.{PSQLException, PSQLState}

def findUniqueConstraintViolated(throwable: Throwable): Option[String] =
  throwable match {
    case null                                                                                             => None
    case psqlException: PSQLException if psqlException.getSQLState == PSQLState.UNIQUE_VIOLATION.getState =>
      Option(psqlException.getServerErrorMessage).flatMap(serverErrorMessage =>
        Option(serverErrorMessage.getConstraint)
      )
    case other => Option(other.getCause).filterNot(_ eq other).flatMap(findUniqueConstraintViolated)
  }

def catchUniqueConstraintViolation(
    errorMessage: String,
    uniqueConstraintViolationMessage: PartialFunction[String, String],
)(
    throwable: Throwable
): ServiceError =
  findUniqueConstraintViolated(throwable) match {
    case Some(constraint) =>
      ServiceError.ConflictError.UniqueConstraintViolation(
        uniqueConstraintViolationMessage.applyOrElse(
          constraint,
          (c: String) => s"A unique constraint was violated: [$c]",
        ),
        throwable,
      )
    case None =>
      ServiceError.InternalServerError.RepositoryError(errorMessage, throwable)
  }
