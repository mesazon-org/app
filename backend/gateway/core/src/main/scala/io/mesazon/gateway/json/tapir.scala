package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import sttp.tapir.codec.iron.given
import sttp.tapir.{Schema, SchemaType}

object tapir {
  private final case class TapirServerErrorBody(code: String, message: String)

  private given JsonValueCodec[TapirServerErrorBody] = JsonCodecMaker.make[TapirServerErrorBody]

  private lazy val tapirServerErrorBodySchema: Schema[TapirServerErrorBody] = Schema.derived[TapirServerErrorBody]

  private lazy val tapirServerErrorByCode: Map[String, TapirServerError] =
    TapirServerError.values.map(tapirServerError => tapirServerError.code -> tapirServerError).toMap

  private given catalogueItemVariantAttributeNameKeyCodec: JsonKeyCodec[CatalogueItemVariantAttributeName] =
    new JsonKeyCodec[CatalogueItemVariantAttributeName] {
      override def decodeKey(in: JsonReader): CatalogueItemVariantAttributeName = {
        val catalogueItemVariantAttributeNameRaw = in.readKeyAsString()
        CatalogueItemVariantAttributeName
          .either(catalogueItemVariantAttributeNameRaw)
          .fold(
            refinementError =>
              in.decodeError(
                s"Invalid CatalogueItemVariantAttributeName: [$catalogueItemVariantAttributeNameRaw], error: [$refinementError]"
              ),
            identity,
          )
      }

      override def encodeKey(
          catalogueItemVariantAttributeName: CatalogueItemVariantAttributeName,
          out: JsonWriter,
      ): Unit =
        out.writeKey(catalogueItemVariantAttributeName.value)
    }

  given addressTypeSchema: Schema[AddressType] =
    Schema.derivedEnumeration[AddressType].defaultStringBased

  given addressSchema: Schema[Address] = Schema.derived[Address]

  given addressEntrySchema: Schema[AddressEntry] = Schema.derived[AddressEntry]

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

  given currencySchema: Schema[Currency] =
    Schema.derivedEnumeration[Currency](
      encode = Some(_.toString),
      schemaType = SchemaType.SString(),
    )

  given priceSchema: Schema[Price] = Schema.derived[Price]

  given catalogueItemVariantUnitSchema: Schema[CatalogueItemVariantUnit] =
    Schema.derivedEnumeration[CatalogueItemVariantUnit](
      encode = Some(_.toString),
      schemaType = SchemaType.SString(),
    )

  given catalogueItemVariantUnitPerUnitSchema: Schema[CatalogueItemVariantUnit.PerUnit] =
    Schema.derivedEnumeration[CatalogueItemVariantUnit.PerUnit](
      encode = Some(_.toString),
      schemaType = SchemaType.SString(),
    )

  given catalogueItemVariantUnitUnitSizeSchema: Schema[CatalogueItemVariantUnit.UnitSize] =
    Schema.derivedEnumeration[CatalogueItemVariantUnit.UnitSize](
      encode = Some(_.toString),
      schemaType = SchemaType.SString(),
    )

  given catalogueItemVariantQuantitySchema: Schema[CatalogueItemVariantQuantity] =
    Schema.derived[CatalogueItemVariantQuantity]

  given catalogueItemVariantPerUnitSchema: Schema[CatalogueItemVariantPerUnit] =
    Schema.derived[CatalogueItemVariantPerUnit]

  given catalogueItemVariantUnitSizeSchema: Schema[CatalogueItemVariantUnitSize] =
    Schema.derived[CatalogueItemVariantUnitSize]

  given catalogueItemVariantSaleUnitSchema: Schema[CatalogueItemVariantSaleUnit] =
    Schema.derived[CatalogueItemVariantSaleUnit]

  given catalogueItemVariantAttributesSchema
      : Schema[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] =
    summon[Schema[Map[String, String]]]
      .as[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]]

  given catalogueItemVariantCandidateSchema: Schema[CatalogueItemVariantCandidate] =
    Schema.derived[CatalogueItemVariantCandidate]

  given catalogueItemCandidateSchema: Schema[CatalogueItemCandidate] = Schema.derived[CatalogueItemCandidate]

  given catalogueFileExtractPostResponseSchema: Schema[CatalogueFileExtractPostResponse] =
    Schema.derived[CatalogueFileExtractPostResponse]

  given addressTypeCodec: JsonValueCodec[AddressType] = JsonCodecMaker.makeWithoutDiscriminator[AddressType]

  given currencyCodec: JsonValueCodec[Currency] = JsonCodecMaker.makeWithoutDiscriminator[Currency]

  given catalogueItemVariantUnitCodec: JsonValueCodec[CatalogueItemVariantUnit] =
    JsonCodecMaker.makeWithoutDiscriminator[CatalogueItemVariantUnit]

  given catalogueItemVariantUnitPerUnitCodec: JsonValueCodec[CatalogueItemVariantUnit.PerUnit] =
    JsonCodecMaker.makeWithoutDiscriminator[CatalogueItemVariantUnit.PerUnit]

  given catalogueItemVariantUnitUnitSizeCodec: JsonValueCodec[CatalogueItemVariantUnit.UnitSize] =
    JsonCodecMaker.makeWithoutDiscriminator[CatalogueItemVariantUnit.UnitSize]

  given priceCodec: JsonValueCodec[Price] = JsonCodecMaker.make[Price]

  given catalogueItemVariantQuantityCodec: JsonValueCodec[CatalogueItemVariantQuantity] =
    JsonCodecMaker.make[CatalogueItemVariantQuantity]

  given catalogueItemVariantPerUnitCodec: JsonValueCodec[CatalogueItemVariantPerUnit] =
    JsonCodecMaker.make[CatalogueItemVariantPerUnit]

  given catalogueItemVariantUnitSizeCodec: JsonValueCodec[CatalogueItemVariantUnitSize] =
    JsonCodecMaker.make[CatalogueItemVariantUnitSize]

  given catalogueItemVariantSaleUnitCodec: JsonValueCodec[CatalogueItemVariantSaleUnit] =
    JsonCodecMaker.make[CatalogueItemVariantSaleUnit]

  given catalogueItemVariantCandidateCodec: JsonValueCodec[CatalogueItemVariantCandidate] =
    JsonCodecMaker.make[CatalogueItemVariantCandidate]

  given catalogueItemCandidateCodec: JsonValueCodec[CatalogueItemCandidate] =
    JsonCodecMaker.make[CatalogueItemCandidate]

  given catalogueFileExtractPostResponseCodec: JsonValueCodec[CatalogueFileExtractPostResponse] =
    JsonCodecMaker.make[CatalogueFileExtractPostResponse]

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
