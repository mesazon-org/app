package io.rikkos.gateway.it.codec

import io.circe.*
import io.circe.parser.*
import smithy4s.Schema
import smithy4s.json.Json as SmithyJson
import zio.NonEmptyChunk

given [T: Schema as schema]: Encoder[T] = Encoder.instance(data =>
  parse(SmithyJson.payloadCodecs.encoders.fromSchema(schema).encode(data).toUTF8String).toTry.get
)

given [T: Encoder as encoder]: Encoder[NonEmptyChunk[T]] = Encoder.encodeList[T].contramap[NonEmptyChunk[T]](_.toList)
