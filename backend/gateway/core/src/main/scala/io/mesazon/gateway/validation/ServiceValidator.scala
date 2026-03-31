package io.mesazon.gateway.validation

import io.mesazon.domain.gateway.*
import zio.*

trait ServiceValidator[A, B] {
  def validate(rawData: A): IO[ServiceError.BadRequestError.FormValidationError, B]
}
