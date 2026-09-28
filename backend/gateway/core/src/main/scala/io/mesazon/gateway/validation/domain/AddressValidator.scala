package io.mesazon.gateway.validation.domain

import cats.data.ValidatedNec
import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import zio.{UIO, ZIO, ZLayer}

import scala.annotation.unused

final class AddressValidator {

  def validate[E](
      @unused addressLine1Raw: String,
      @unused addressLine2RawOpt: Option[String],
      @unused cityRaw: String,
      @unused postalCodeRaw: String,
      @unused countryRaw: String,
      @unused addressType: AddressType,
      @unused addressEntryConstructor: AddressEntry => Either[String, E],
  ): UIO[ValidatedNec[InvalidFieldError, E]] =
    ZIO.die(new NotImplementedError("AddressValidator.validate is not implemented yet"))
}

object AddressValidator {

  val live = ZLayer.derive[AddressValidator]
}
