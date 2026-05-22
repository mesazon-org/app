package io.mesazon.gateway.validation.domain

import cats.data.ValidatedNec
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.*

trait DomainValidator[A, B] {
  def validate(dataRaw: A): UIO[ValidatedNec[InvalidFieldError, B]]
}
