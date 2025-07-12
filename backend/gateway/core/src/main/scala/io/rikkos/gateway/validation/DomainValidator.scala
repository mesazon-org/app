package io.rikkos.gateway.validation

import cats.data.ValidatedNec
import io.rikkos.domain.ServiceError.BadRequestError.InvalidFieldError
import zio.*

trait DomainValidator[A, B] {
  def validate(rawData: A): UIO[ValidatedNec[InvalidFieldError, B]]
}
