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

// Hand-written, not macro-derived: JsonCodecMaker's auto-derivation of a JsonKeyCodec for a refined type
// StackOverflows while dealiasing the chained opaque types (RefinedType.T through Iron's IronType). This
// bypasses that path the same way the Read/Write/Put/Get/Meta givens above bypass macro derivation for
// refined types, trusting that a FinalType value stored as a jsonb map key was already validated on write.
given [WrappedType](using
    mirror: RefinedType.Mirror[WrappedType] { type BaseType = String }
): JsonKeyCodec[mirror.FinalType] =
  new JsonKeyCodec[mirror.FinalType] {
    override def decodeKey(in: JsonReader): mirror.FinalType =
      in.readKeyAsString().asInstanceOf[mirror.FinalType]

    override def encodeKey(x: mirror.FinalType, out: JsonWriter): Unit =
      out.writeKey(x.asInstanceOf[String])
  }

inline given [A <: Enum](using mirror: Mirror.SumOf[A]): Get[A] = Get.deriveEnumString[A]

inline given [A <: Enum](using mirror: Mirror.SumOf[A]): Put[A] = Put.deriveEnumString[A]

private[queries] def jsonbMeta[A](using JsonValueCodec[A]): Meta[A] =
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
private given addressTypeCodec: JsonValueCodec[AddressType] = JsonCodecMaker.makeWithoutDiscriminator
given organizationEmailEntryRequestsMeta: Meta[List[OrganizationEmailEntryRequest]]             = jsonbMeta
given organizationPhoneNumberEntryRequestsMeta: Meta[List[OrganizationPhoneNumberEntryRequest]] = jsonbMeta
