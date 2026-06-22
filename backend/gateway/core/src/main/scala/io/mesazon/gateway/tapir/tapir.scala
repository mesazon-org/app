package io.mesazon.gateway.tapir

import io.github.iltotore.iron.constraint.all.Trimmed
import io.mesazon.domain.gateway.TapirServerError
import sttp.model.StatusCode
import sttp.tapir.codec.iron.ValidatorForPredicate
import sttp.tapir.{ValidationError, Validator}
import zio.*

type TapirTask[A] = IO[(StatusCode, TapirServerError), A]

given ValidatorForPredicate[String, Trimmed] = new ValidatorForPredicate[String, Trimmed] {
  override def validator: Validator[String] =
    Validator.pattern[String]("""^$|^\S(?:.*\S)?$""")

  override def makeErrors(value: String, errorMessage: String): List[ValidationError[?]] =
    validator.apply(value).map(_.copy(customMessage = Some(errorMessage)))
}
