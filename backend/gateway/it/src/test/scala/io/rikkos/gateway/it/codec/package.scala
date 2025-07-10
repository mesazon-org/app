package io.rikkos.gateway.it.codec

import org.http4s.*
import org.http4s.headers.`Content-Type`
import smithy4s.Schema
import smithy4s.json.Json
import zio.*

given [T: Schema as schema]: EntityEncoder[Task, T] =
  EntityEncoder
    .byteArrayEncoder[Task]
    .contramap((data: T) => Json.payloadCodecs.encoders.fromSchema(schema).encode(data).toArray)
    .withContentType(`Content-Type`(MediaType.application.json, Some(Charset.`UTF-8`)))
