package io.mesazon.gateway.repository.queries

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import org.postgresql.util.PGobject
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

private def jsonbMeta[A](using JsonValueCodec[A]): Meta[A] =
  Meta.Advanced
    .other[PGobject]("jsonb")
    .timap(pgObject => readFromString[A](pgObject.getValue)) { a =>
      val pgObject = new PGobject
      pgObject.setType("jsonb")
      pgObject.setValue(writeToString(a))
      pgObject
    }

private given organizationEmailEntryRequestsCodec: JsonValueCodec[List[OrganizationEmailEntryRequest]] =
  JsonCodecMaker.make
private given organizationPhoneNumberEntryRequestsCodec: JsonValueCodec[List[OrganizationPhoneNumberEntryRequest]] =
  JsonCodecMaker.make

given organizationEmailEntryRequestsMeta: Meta[List[OrganizationEmailEntryRequest]]             = jsonbMeta
given organizationPhoneNumberEntryRequestsMeta: Meta[List[OrganizationPhoneNumberEntryRequest]] = jsonbMeta
