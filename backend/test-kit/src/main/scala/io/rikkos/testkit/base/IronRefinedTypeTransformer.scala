package io.rikkos.testkit.base

import io.github.iltotore.iron.RefinedType
import io.scalaland.chimney.Transformer

trait IronRefinedTypeTransformer {

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType]
  ): Transformer[WrappedType, mirror.BaseType] =
    (value: WrappedType) => value.asInstanceOf[mirror.BaseType]

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType]
  ): Transformer[mirror.BaseType, WrappedType] =
    (value: mirror.BaseType) => value.asInstanceOf[WrappedType]
}
