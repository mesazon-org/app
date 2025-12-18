package io.rikkos.gateway

import doobie.util.{Get, Put}
import io.github.iltotore.iron.RefinedType

package object query {

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType],
      put: Put[mirror.BaseType],
  ): Put[mirror.FinalType] = Put[mirror.BaseType].tcontramap(i => i.asInstanceOf[mirror.BaseType])

  given [WrappedType](using
      mirror: RefinedType.Mirror[WrappedType],
      get: Get[mirror.BaseType],
  ): Get[mirror.FinalType] = Get[mirror.BaseType].tmap(_.asInstanceOf[mirror.FinalType])
}
