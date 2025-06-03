package io.rikkos.testkit.base

import io.github.iltotore.iron.RefinedType
import io.scalaland.chimney.Transformer

trait IronRefinedTypeTransformer {

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType]
  ): Transformer[mirror.FinalType, mirror.BaseType] =
    (value: mirror.FinalType) => value.asInstanceOf[mirror.BaseType]
}
