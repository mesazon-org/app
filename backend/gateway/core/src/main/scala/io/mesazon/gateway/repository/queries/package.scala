package io.mesazon.gateway.repository.queries

import doobie.util.{Get, Put}
import io.github.iltotore.iron.RefinedType

import scala.deriving.Mirror
import scala.reflect.Enum

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    put: Put[mirror.BaseType],
): Put[mirror.FinalType] = Put[mirror.BaseType].tcontramap(i => i.asInstanceOf[mirror.BaseType])

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    get: Get[mirror.BaseType],
): Get[mirror.FinalType] = Get[mirror.BaseType].tmap(_.asInstanceOf[mirror.FinalType])

inline given [A <: Enum](using mirror: Mirror.SumOf[A]): Get[A] = Get.deriveEnumString[A]

inline given [A <: Enum](using mirror: Mirror.SumOf[A]): Put[A] = Put.deriveEnumString[A]
