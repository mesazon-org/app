package io.mesazon.gateway.utils

import io.github.iltotore.iron.RefinedType
import io.scalaland.chimney.Transformer

given ironRefinedTypeToTargetTransformer[WrappedType, TargetType <: Product](using
    mirror: RefinedType.Mirror[WrappedType],
    transformer: Transformer.AutoDerived[mirror.BaseType, TargetType],
): Transformer[WrappedType, TargetType] =
  wrappedType => transformer.transform(wrappedType.asInstanceOf[mirror.BaseType])
