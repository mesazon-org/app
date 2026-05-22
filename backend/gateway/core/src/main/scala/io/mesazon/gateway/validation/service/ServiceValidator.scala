package io.mesazon.gateway.validation.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.validation.domain.DomainValidator
import zio.*

trait ServiceValidator[A, B] {
  def domainValidator: DomainValidator[A, B]

  final def validate(rawData: A): IO[ServiceError.BadRequestError.ValidationError, B] =
    domainValidator
      .validate(rawData)
      .flatMap(validated => ZIO.fromEither(validated.toEither))
      .mapError(_.toNonEmptyList.toList)
      .mapError(ServiceError.BadRequestError.ValidationError.apply)
}
