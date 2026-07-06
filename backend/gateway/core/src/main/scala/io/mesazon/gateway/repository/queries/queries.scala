package io.mesazon.gateway.repository.queries

import io.github.iltotore.iron.RefinedType
import org.typelevel.doobie.{Get, Meta, Put, Read, Write}

import scala.deriving.Mirror
import scala.reflect.Enum

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    read: Read[mirror.BaseType],
): Read[mirror.FinalType] = read.asInstanceOf[Read[mirror.FinalType]]

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    write: Write[mirror.BaseType],
): Write[mirror.FinalType] = write.asInstanceOf[Write[mirror.FinalType]]

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    put: Put[mirror.BaseType],
): Put[mirror.FinalType] = put.asInstanceOf[Put[mirror.FinalType]]

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    get: Get[mirror.BaseType],
): Get[mirror.FinalType] = get.asInstanceOf[Get[mirror.FinalType]]

given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType],
    meta: Meta[mirror.BaseType],
): Meta[mirror.FinalType] =
  meta.asInstanceOf[Meta[mirror.FinalType]]

inline given [A <: Enum](using mirror: Mirror.SumOf[A]): Get[A] = Get.deriveEnumString[A]

inline given [A <: Enum](using mirror: Mirror.SumOf[A]): Put[A] = Put.deriveEnumString[A]
