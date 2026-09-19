package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given

object tapir {
  private final case class TapirServerErrorBody(code: String, message: String)

  private given JsonValueCodec[TapirServerErrorBody] = JsonCodecMaker.make[TapirServerErrorBody]

  private lazy val tapirServerErrorBodySchema: Schema[TapirServerErrorBody] = Schema.derived[TapirServerErrorBody]

  private lazy val tapirServerErrorByCode: Map[String, TapirServerError] =
    TapirServerError.values.map(tapirServerError => tapirServerError.code -> tapirServerError).toMap

  given extractCustomerEmailEntrySchema: Schema[ExtractCustomerEmailEntry] = Schema.derived[ExtractCustomerEmailEntry]

  given extractCustomerPhoneNumberSchema: Schema[ExtractCustomerPhoneNumber] =
    Schema.derived[ExtractCustomerPhoneNumber]

  given extractCustomerPhoneNumberEntrySchema: Schema[ExtractCustomerPhoneNumberEntry] =
    Schema.derived[ExtractCustomerPhoneNumberEntry]

  given extractCustomerIndividualSchema: Schema[ExtractCustomerIndividual] = Schema.derived[ExtractCustomerIndividual]

  given extractCustomerBusinessContactSchema: Schema[ExtractCustomerBusinessContact] =
    Schema.derived[ExtractCustomerBusinessContact]

  given extractCustomerBusinessSchema: Schema[ExtractCustomerBusiness] = Schema.derived[ExtractCustomerBusiness]

  given extractCustomerIndividualDataSchema: Schema[ExtractCustomerIndividualData] =
    Schema.derived[ExtractCustomerIndividualData]

  given extractCustomerBusinessDataSchema: Schema[ExtractCustomerBusinessData] =
    Schema.derived[ExtractCustomerBusinessData]

  given extractCustomersPostResponseSchema: Schema[ExtractCustomersPostResponse] =
    Schema.derived[ExtractCustomersPostResponse]

  given phoneNumberSchema: Schema[PhoneNumber] = Schema.derived[PhoneNumber]

  given customerEmailEntryRequestSchema: Schema[CustomerEmailEntryRequest] =
    Schema.derived[CustomerEmailEntryRequest]

  given customerPhoneNumberEntryRequestSchema: Schema[CustomerPhoneNumberEntryRequest] =
    Schema.derived[CustomerPhoneNumberEntryRequest]

  given insertCustomerBusinessContactSchema: Schema[InsertCustomerBusinessContact] =
    Schema.derived[InsertCustomerBusinessContact]

  given insertCustomerIndividualPostRequestSchema: Schema[InsertCustomerIndividualPostRequest] =
    Schema.derived[InsertCustomerIndividualPostRequest]

  given insertCustomerBusinessPostRequestSchema: Schema[InsertCustomerBusinessPostRequest] =
    Schema.derived[InsertCustomerBusinessPostRequest]

  given extractCustomersPostResponseCodec: JsonValueCodec[ExtractCustomersPostResponse] =
    JsonCodecMaker.make[ExtractCustomersPostResponse]

  given tapirServerErrorSchemaFallback: Schema[TapirServerError] =
    tapirServerErrorBodySchema
      .as[TapirServerError]
      .name(Schema.SName("ServerError"))

  given tapirServerErrorCodec: JsonValueCodec[TapirServerError] = new JsonValueCodec[TapirServerError] {
    private val bodyCodec = summon[JsonValueCodec[TapirServerErrorBody]]

    override def decodeValue(in: JsonReader, default: TapirServerError): TapirServerError = {
      val body = bodyCodec.decodeValue(in, TapirServerErrorBody("", ""))
      tapirServerErrorByCode.getOrElse(body.code, in.decodeError(s"Unknown TapirServerError code: [${body.code}]"))
    }

    override def encodeValue(tapirServerError: TapirServerError, out: JsonWriter): Unit =
      bodyCodec.encodeValue(TapirServerErrorBody(tapirServerError.code, tapirServerError.message), out)

    override def nullValue: TapirServerError = null
  }

  val tapirServerErrorSchemas: Map[TapirServerError, Schema[TapirServerError]] =
    TapirServerError.values.map(error => error -> tapirServerErrorSchema(error)).toMap

  private def tapirServerErrorSchema(tapirServerError: TapirServerError): Schema[TapirServerError] =
    tapirServerErrorBodySchema
      .modify(_.code)(_.default(tapirServerError.code))
      .modify(_.message)(_.default(tapirServerError.message))
      .as[TapirServerError]
      .name(Schema.SName(tapirServerError.schemaName))

}
