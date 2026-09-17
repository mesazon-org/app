package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import sttp.apispec.{AnySchema, Schema as ApiSchema, SchemaLike}
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given
import sttp.tapir.docs.apispec.schema.TapirSchemaToJsonSchema

object ai {

  given assistantResponseSchema: Schema[AssistantResponse] = Schema.derived[AssistantResponse]

  given assistantResponseCodec: JsonValueCodec[AssistantResponse] = JsonCodecMaker.make[AssistantResponse]

  given extractCustomersPostResponseOpenAIJsonSchema: OpenAIJsonSchema[ExtractCustomersPostResponse] =
    fromTapir(tapir.extractCustomersPostResponseSchema)

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
