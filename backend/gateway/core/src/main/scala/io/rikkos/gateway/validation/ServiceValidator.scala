package io.rikkos.gateway.validation

import io.rikkos.domain.gateway.*
import zio.*

trait ServiceValidator[A, B] {
  def validate(rawData: A): IO[ServiceError.BadRequestError, B]
}
