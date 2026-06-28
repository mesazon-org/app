package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given

given Schema[AssistantResponse] = Schema.derived[AssistantResponse]

given JsonValueCodec[AssistantResponse] = JsonCodecMaker.make[AssistantResponse]

private final case class TapirServerErrorBody(code: String, message: String)
private given JsonValueCodec[TapirServerErrorBody] = JsonCodecMaker.make[TapirServerErrorBody]

private val tapirServerErrorByCode: Map[String, TapirServerError] =
  TapirServerError.values.map(tapirServerError => tapirServerError.code -> tapirServerError).toMap

private def tapirServerErrorSchema(tapirServerError: TapirServerError): Schema[TapirServerError] =
  Schema
    .derived[TapirServerErrorBody]
    .modify(_.code)(_.default(tapirServerError.code))
    .modify(_.message)(_.default(tapirServerError.message))
    .as[TapirServerError]
    .name(Schema.SName(tapirServerError.schemaName))

val tapirServerErrorSchemas: Map[TapirServerError, Schema[TapirServerError]] =
  TapirServerError.values.map(error => error -> tapirServerErrorSchema(error)).toMap

// Fallback schema used where a specific variant is not known (e.g. decode-failure handler).
given Schema[TapirServerError] =
  Schema
    .derived[TapirServerErrorBody]
    .as[TapirServerError]
    .name(Schema.SName("ServerError"))

given JsonValueCodec[TapirServerError] = new JsonValueCodec[TapirServerError] {
  private val bodyCodec = summon[JsonValueCodec[TapirServerErrorBody]]

  override def decodeValue(in: JsonReader, default: TapirServerError): TapirServerError = {
    val body = bodyCodec.decodeValue(in, TapirServerErrorBody("", ""))
    tapirServerErrorByCode.getOrElse(body.code, in.decodeError(s"Unknown TapirServerError code: [${body.code}]"))
  }

  override def encodeValue(tapirServerError: TapirServerError, out: JsonWriter): Unit =
    bodyCodec.encodeValue(TapirServerErrorBody(tapirServerError.code, tapirServerError.message), out)

  override def nullValue: TapirServerError = null
}
