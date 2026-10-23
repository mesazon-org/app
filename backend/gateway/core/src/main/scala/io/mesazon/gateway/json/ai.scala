package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.json.tapir.{
  catalogueItemVariantSaleUnitCodec,
  catalogueItemVariantSaleUnitSchema,
  currencyCodec,
  priceSchema,
}
import sttp.apispec.{AnySchema, Schema as ApiSchema, SchemaLike}
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

object ai {

  given assistantResponseSchema: Schema[AssistantResponse] = Schema.derived[AssistantResponse]

  given assistantResponseCodec: JsonValueCodec[AssistantResponse] = JsonCodecMaker.make[AssistantResponse]

  private case class CatalogueItemVariantExtractAttribute(
      name: CatalogueItemVariantAttributeName,
      value: CatalogueItemVariantAttributeValue,
  )

  private given catalogueItemVariantExtractAttributeSchema: Schema[CatalogueItemVariantExtractAttribute] =
    Schema.derived[CatalogueItemVariantExtractAttribute]

  private given catalogueItemVariantAttributesSchema
      : Schema[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] =
    summon[Schema[List[CatalogueItemVariantExtractAttribute]]]
      .as[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]]

  private given catalogueItemVariantCandidateSchema: Schema[CatalogueItemVariantCandidate] =
    Schema.derived[CatalogueItemVariantCandidate]

  private given catalogueItemCandidateSchema: Schema[CatalogueItemCandidate] = Schema.derived[CatalogueItemCandidate]

  private val catalogueItemVariantExtractAttributesCodec: JsonValueCodec[List[CatalogueItemVariantExtractAttribute]] =
    JsonCodecMaker.make[List[CatalogueItemVariantExtractAttribute]]

  private given catalogueItemVariantAttributesCodec
      : JsonValueCodec[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] =
    new JsonValueCodec[Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue]] {
      override def decodeValue(
          in: JsonReader,
          default: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
      ): Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue] =
        catalogueItemVariantExtractAttributesCodec
          .decodeValue(in, Nil)
          .iterator
          .map(attribute => attribute.name -> attribute.value)
          .toMap

      override def encodeValue(
          attributes: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue],
          out: JsonWriter,
      ): Unit =
        catalogueItemVariantExtractAttributesCodec.encodeValue(
          attributes.toList.map { case (name, value) => CatalogueItemVariantExtractAttribute(name, value) },
          out,
        )

      override def nullValue: Map[CatalogueItemVariantAttributeName, CatalogueItemVariantAttributeValue] = Map.empty
    }

  private given catalogueItemVariantCandidateCodec: JsonValueCodec[CatalogueItemVariantCandidate] =
    JsonCodecMaker.make[CatalogueItemVariantCandidate]

  private given catalogueItemCandidateCodec: JsonValueCodec[CatalogueItemCandidate] =
    JsonCodecMaker.make[CatalogueItemCandidate]

  given catalogueFileExtractSchema: Schema[CatalogueFileExtract] = Schema.derived[CatalogueFileExtract]

  given catalogueFileExtractCodec: JsonValueCodec[CatalogueFileExtract] =
    JsonCodecMaker.make[CatalogueFileExtract]

  given catalogueFileExtractOpenAIJsonSchema: OpenAIJsonSchema[CatalogueFileExtract] =
    fromTapir(catalogueFileExtractSchema)

  given extractCustomersPostResponseOpenAIJsonSchema: OpenAIJsonSchema[ExtractCustomersPostResponse] =
    fromTapir(tapir.extractCustomersPostResponseSchema)

  given noteCompactionOutputSchema: Schema[NoteCompactionOutput] = Schema.derived[NoteCompactionOutput]

  given noteCompactionOutputOpenAIJsonSchema: OpenAIJsonSchema[NoteCompactionOutput] =
    fromTapir(noteCompactionOutputSchema)

  given noteCompactionOutputCodec: JsonValueCodec[NoteCompactionOutput] = JsonCodecMaker.make[NoteCompactionOutput]

  private def requireAllPropertiesLike(schemaLike: SchemaLike): SchemaLike = schemaLike match {
    case schema: ApiSchema    => requireAllProperties(schema)
    case anySchema: AnySchema => anySchema
  }

  private def requireAllProperties(schema: ApiSchema): ApiSchema = {
    val isObject = schema.`type`.exists(_.contains(sttp.apispec.SchemaType.Object))

    schema.copy(
      $defs = schema.$defs.map(_.map((name, nested) => name -> requireAllPropertiesLike(nested))),
      allOf = schema.allOf.map(requireAllPropertiesLike),
      anyOf = schema.anyOf.map(requireAllPropertiesLike),
      oneOf = schema.oneOf.map(requireAllPropertiesLike),
      not = schema.not.map(requireAllPropertiesLike),
      `if` = schema.`if`.map(requireAllPropertiesLike),
      `then` = schema.`then`.map(requireAllPropertiesLike),
      `else` = schema.`else`.map(requireAllPropertiesLike),
      dependentSchemas = schema.dependentSchemas.map((name, nested) => name -> requireAllPropertiesLike(nested)),
      prefixItems = schema.prefixItems.map(_.map(requireAllPropertiesLike)),
      items = schema.items.map(requireAllPropertiesLike),
      contains = schema.contains.map(requireAllPropertiesLike),
      unevaluatedItems = schema.unevaluatedItems.map(requireAllPropertiesLike),
      required = if (isObject) schema.properties.keys.toList else schema.required,
      properties = schema.properties.map((name, nested) => name -> requireAllPropertiesLike(nested)),
      patternProperties =
        schema.patternProperties.map((pattern, nested) => pattern -> requireAllPropertiesLike(nested)),
      additionalProperties = schema.additionalProperties.map(requireAllPropertiesLike),
      propertyNames = schema.propertyNames.map(requireAllPropertiesLike),
      unevaluatedProperties = schema.unevaluatedProperties.map(requireAllPropertiesLike),
    )
  }

  // sttp-ai applies OpenAI's strict object/required/nullability rules during encoding. Supplying every source object
  // property as required preserves non-null list fields; nullable Option fields remain nullable in the Tapir schema.
  private def responseSchema[A](using tapirSchema: Schema[A]): ApiSchema =
    requireAllProperties(
      TapirSchemaToJsonSchema(tapirSchema, markOptionsAsNullable = true)
    )

  def fromTapir[A](tapirSchema: Schema[A]): OpenAIJsonSchema[A] = new OpenAIJsonSchema[A] {
    override val schema: ApiSchema = responseSchema(using tapirSchema)
  }
}
