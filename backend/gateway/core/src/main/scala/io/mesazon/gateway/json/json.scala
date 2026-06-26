package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given

given Schema[AssistantResponse] = Schema.derived[AssistantResponse]

given JsonValueCodec[AssistantResponse] = JsonCodecMaker.make[AssistantResponse]

// TapirServerError is a sealed hierarchy of field-less case objects, so deriving its schema/codec directly yields an
// empty `{}` body. It is exposed over the wire as a flat { code, message } object; the wire record below pins that JSON
// shape (and OpenAPI schema) for every variant.
private final case class TapirServerErrorBody(code: String, message: String)
private given JsonValueCodec[TapirServerErrorBody] = JsonCodecMaker.make[TapirServerErrorBody]

private val tapirServerErrorByCode: Map[String, TapirServerError] =
  List(
    TapirServerError.BadRequestError,
    TapirServerError.UnauthorizedError,
    TapirServerError.NotFoundError,
    TapirServerError.InternalServerError,
    TapirServerError.ServiceUnavailableError,
  ).map(tapirServerError => tapirServerError.code -> tapirServerError).toMap

given Schema[TapirServerError] =
  Schema.derived[TapirServerErrorBody].as[TapirServerError].name(Schema.SName("TapirServerError"))

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
