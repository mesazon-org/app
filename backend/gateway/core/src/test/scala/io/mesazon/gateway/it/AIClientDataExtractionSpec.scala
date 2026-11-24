package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.circe.{parser, Json}
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.{ai, OpenAIJsonSchema}
import io.mesazon.gateway.utils.FileScannedPath
import io.mesazon.testkit.base.*
import io.mesazon.wiremock.WiremockClient
import io.mesazon.wiremock.WiremockClient.WiremockClientConfig
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import sttp.ai.openai.requests.completions.chat.SchemaSupport
import sttp.client4.*
import sttp.client4.jsoniter.*
import sttp.model.{MediaType, StatusCode}
import sttp.tapir.Schema
import zio.*
import zio.stream.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

class AIClientDataExtractionSpec extends ZWordSpecBase, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/wiremock.yaml"

  override def exposedServices: Set[ExposedService] = WiremockClient.ExposedServices

  private val imageBytes = Array[Byte](1, 2, 3, 4, 5)
  private val pdfBytes   = "%PDF-1.7\n%%EOF\n".getBytes(StandardCharsets.UTF_8)

  private def imageScannedPath(): FileScannedPath = {
    val path = Files.createTempFile("ai-client-data-extraction-spec-image-", ".jpg")
    Files.write(path, imageBytes)
    path.toFile.deleteOnExit()
    FileScannedPath(path)
  }

  private def pdfScannedPath(): FileScannedPath = {
    val path = Files.createTempFile("ai-client-data-extraction-spec-pdf-", ".pdf")
    Files.write(path, pdfBytes)
    path.toFile.deleteOnExit()
    FileScannedPath(path)
  }

  private def csvRecordStream(csvText: String): ZStream[Scope, ServiceError, CSVRecord] = {
    val csvPath = Files.createTempFile("ai-client-data-extraction-spec-csv-", ".csv")
    Files.writeString(csvPath, csvText, StandardCharsets.UTF_8)
    csvPath.toFile.deleteOnExit()

    ZStream
      .acquireReleaseWith(
        ZIO
          .attemptBlocking(
            CSVParser.parse(Files.newBufferedReader(csvPath, StandardCharsets.UTF_8), CSVFormat.DEFAULT)
          )
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(error)))
      )(csvParser => ZIO.attemptBlocking(csvParser.close()).ignoreLogged)
      .flatMap(csvParser =>
        ZStream
          .blocking(ZStream.fromJavaIterator(csvParser.iterator()))
          .mapError(error => ServiceError.InternalServerError.UnexpectedError("File is not valid CSV", Some(error)))
      )
  }

  private lazy val extractedTestResultSchema: Schema[ExtractedTestResult] = Schema.derived[ExtractedTestResult]

  case class ExtractedTestResult(value: String)
  case class Context(aiClientDataExtractionConfig: AIClientDataExtractionConfig, wiremockClient: WiremockClient)
  private case class AIRequestLogResponse(requests: Seq[AIRequestLog])
  private case class AIRequestLog(request: AIRequestLogged)
  private case class AIRequestLogged(url: String, loggedDate: Long, body: String)

  given extractedTestResultOpenAIJsonSchema: OpenAIJsonSchema[ExtractedTestResult] =
    ai.fromTapir(extractedTestResultSchema)
  given JsonValueCodec[ExtractedTestResult] = JsonCodecMaker.make[ExtractedTestResult]
  private given aiRequestLogResponseCodec: JsonValueCodec[AIRequestLogResponse] =
    JsonCodecMaker.make[AIRequestLogResponse]

  private def requestBodies(wiremockClient: WiremockClient): Seq[Json] = {
    val aiRequestLogResponse = basicRequest
      .get(wiremockClient.config.baseUri.withPath("__admin", "requests"))
      .response(asJsonOrFail[AIRequestLogResponse])
      .send(wiremockClient.backend)
      .orDie
      .zioValue
      .body

    aiRequestLogResponse.requests
      .filter(_.request.url == "/v1/responses")
      .sortBy(_.request.loggedDate)
      .map(aiRequestLog => parser.parse(aiRequestLog.request.body).fold(throw _, identity))
  }

  private def findPropertySchema(json: Json, propertyName: String): Option[Json] = {
    val directPropertySchema = json.hcursor.downField("properties").downField(propertyName).focus

    directPropertySchema.orElse(
      json.arrayOrObject(
        None,
        _.iterator.flatMap(findPropertySchema(_, propertyName)).nextOption(),
        _.values.iterator.flatMap(findPropertySchema(_, propertyName)).nextOption(),
      )
    )
  }

  def withContext[A](f: Context => A): A = withContainers { container =>
    val wiremockClientConfig = WiremockClientConfig.from(container)
    val wiremockClient       = ZIO
      .service[WiremockClient]
      .provide(
        WiremockClient.live,
        ZLayer.succeed(wiremockClientConfig),
        SttpTestBackend.live,
      )
      .zioValue

    val aiClientDataExtractionConfig = AIClientDataExtractionConfig(
      scheme = "http",
      host = wiremockClientConfig.host,
      port = wiremockClientConfig.port,
      apiKey = "test-api-key",
      requestTimeout = Duration.fromSeconds(60),
      sendMaxRetries = 2,
      sendRetryDelay = Duration.fromMillis(10),
      csvBatchMaxDataRows = 50,
      csvBatchParallelism = 3,
      imageModel = "test-model",
      imageReasoningEffort = "medium",
      pdfModel = "test-pdf-model",
      pdfReasoningEffort = "high",
      csvModel = "test-model",
      csvReasoningEffort = "medium",
      noteCompactionModel = "test-note-compaction-model",
      noteCompactionReasoningEffort = "medium",
      noteCompactionRequestTimeout = Duration.fromSeconds(60),
    )

    f(Context(aiClientDataExtractionConfig, wiremockClient))
  }

  override def beforeAll(): Unit = withContext { context =>
    import context.*

    super.beforeAll()

    eventually {
      val healthCheckStatusResponse = wiremockClient.healthCheck.zioValue

      healthCheckStatusResponse.code shouldBe StatusCode.Ok
      healthCheckStatusResponse.body.status shouldBe "healthy"
    }
  }

  override def afterEach(): Unit = withContext { context =>
    import context.*

    super.afterEach()

    eventually {
      wiremockClient.reset.zioValue.code shouldBe StatusCode.Ok
    }
  }

  "AIClientDataExtraction" when {
    "extractFromImage" should {
      "successfully extract a structured response from an image" in withContext { context =>
        import context.*

        val instructions           = "AI_CLIENT_SPEC_SUCCESS"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val extractedTestResult = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            instructions,
          )
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("extracted-value")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 1

        extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
        extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/responses"
        extractFromImageRequestMappings(0).count shouldBe 1

        val imageInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type"      -> Json.fromString("input_image"),
              "detail"    -> Json.fromString("high"),
              "image_url" -> Json.fromString(
                s"data:${SupportedMediaType.JPEG.mimes.head};base64,${Base64.getEncoder.encodeToString(imageBytes)}"
              ),
            )
          ),
        )
        val responseSchemaExpected =
          SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[ExtractedTestResult]].schema)
        requestBodies(wiremockClient).loneElement shouldBe Json.obj(
          "model"        -> Json.fromString(aiClientDataExtractionConfig.imageModel),
          "instructions" -> Json.fromString(instructions),
          "input"        -> Json.arr(imageInputMessage),
          "reasoning"    -> Json.obj("effort" -> Json.fromString(aiClientDataExtractionConfig.imageReasoningEffort)),
          "store"        -> Json.False,
          "text"         -> Json.obj(
            "format" -> Json.obj(
              "type"   -> Json.fromString("json_schema"),
              "name"   -> Json.fromString("ai_client_response"),
              "strict" -> Json.True,
              "schema" -> responseSchemaExpected,
            )
          ),
        )
      }

      "decode catalogue candidates with nullable fields using a strict schema" in withContext { context =>
        import context.*

        val instructions             = "AI_CLIENT_SPEC_CATALOGUE_SUCCESS"
        val catalogueFileExtractJson =
          """{
            |  "entriesIdentified": 2,
            |  "entriesProcessed": 2,
            |  "candidates": [
            |    {
            |      "name": "Cotton T-Shirt",
            |      "description": "Classic crew neck",
            |      "variants": [
            |        {
            |          "sku": "TS-RED-M",
            |          "label": "Red / Medium",
            |          "description": null,
            |          "attributes": [{"name": "Color", "value": "Red"}, {"name": "Color", "value": "Red"}, {"name": "Color", "value": "Blue"}, {"name": "Size", "value": "M"}, {"name": "Size", "value": "M"}],
            |          "price": {"amount": 12.50, "currency": "EUR", "currencyInferred": true},
            |          "sellingUnit": {
            |            "quantity": {"value": 1, "unit": "Piece"},
            |            "perUnit": null,
            |            "unitSize": null
            |          },
            |          "sellingUnitInferred": true,
            |          "extractionNotes": ["Source label is partly obscured"]
            |        }
            |      ],
            |      "extractionNotes": []
            |    },
            |    {
            |      "name": null,
            |      "description": null,
            |      "variants": [
            |        {
            |          "sku": null,
            |          "label": "Blue / Large",
            |          "description": null,
            |          "attributes": [],
            |          "price": {"amount": 14.00, "currency": null, "currencyInferred": false},
            |          "sellingUnit": null,
            |          "sellingUnitInferred": false,
            |          "extractionNotes": []
            |        }
            |      ],
            |      "extractionNotes": ["No item name is shown"]
            |    }
            |  ],
            |  "emptyEntryRows": [],
            |  "unidentifiedEntryRows": [],
            |  "extractionNotes": ["Image text was clear"]
            |}""".stripMargin
        val openAIResponseBody = Json
          .obj(
            "id"         -> Json.fromString("resp_catalogue_file_extract"),
            "object"     -> Json.fromString("response"),
            "created_at" -> Json.fromLong(1700000000L),
            "model"      -> Json.fromString("test-model"),
            "status"     -> Json.fromString("completed"),
            "output"     -> Json.arr(
              Json.obj(
                "type"    -> Json.fromString("message"),
                "id"      -> Json.fromString("msg_catalogue_file_extract"),
                "role"    -> Json.fromString("assistant"),
                "status"  -> Json.fromString("completed"),
                "content" -> Json.arr(
                  Json.obj(
                    "type"        -> Json.fromString("output_text"),
                    "text"        -> Json.fromString(catalogueFileExtractJson),
                    "annotations" -> Json.arr(),
                  )
                ),
              )
            ),
          )
          .noSpaces
        val mappingJson = Json
          .obj(
            "priority" -> Json.fromInt(1),
            "request"  -> Json.obj(
              "method"       -> Json.fromString("POST"),
              "urlPath"      -> Json.fromString("/v1/responses"),
              "bodyPatterns" -> Json.arr(
                Json.obj("contains" -> Json.fromString(instructions))
              ),
            ),
            "response" -> Json.obj(
              "status"  -> Json.fromInt(200),
              "headers" -> Json.obj(
                "Content-Type" -> Json.fromString(MediaType.ApplicationJson.toString)
              ),
              "body" -> Json.fromString(openAIResponseBody),
            ),
          )
          .noSpaces

        val mappingResponse = basicRequest
          .post(wiremockClient.config.baseUri.withPath("__admin", "mappings"))
          .contentType(MediaType.ApplicationJson)
          .body(mappingJson)
          .send(wiremockClient.backend)
          .orDie
          .zioValue

        mappingResponse.code shouldBe StatusCode.Created

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val catalogueFileExtract = aiClientDataExtraction
          .extractFromImage[CatalogueFileAIExtract](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            instructions,
          )
          .zioValue

        catalogueFileExtract shouldBe CatalogueFileAIExtract(
          entriesIdentified = 2L,
          entriesProcessed = 2L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(CatalogueItemName.assume("Cotton T-Shirt")),
              description = Some(CatalogueItemDescription.assume("Classic crew neck")),
              variants = List(
                CatalogueItemVariantAIExtract(
                  sku = Some(CatalogueItemVariantSku.assume("TS-RED-M")),
                  label = Some(CatalogueItemVariantLabel.assume("Red / Medium")),
                  description = None,
                  attributes = List(
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Color"),
                      CatalogueItemVariantAttributeValue.assume("Red"),
                    ),
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Color"),
                      CatalogueItemVariantAttributeValue.assume("Red"),
                    ),
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Color"),
                      CatalogueItemVariantAttributeValue.assume("Blue"),
                    ),
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Size"),
                      CatalogueItemVariantAttributeValue.assume("M"),
                    ),
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Size"),
                      CatalogueItemVariantAttributeValue.assume("M"),
                    ),
                  ),
                  price = Some(
                    CatalogueItemVariantPriceAIExtract(
                      amount = PriceAmount.assume(BigDecimal("12.50")),
                      currency = Some(Currency.EUR),
                      currencyInferred = true,
                    )
                  ),
                  sellingUnit = Some(
                    CatalogueItemVariantSaleUnit(
                      quantity = CatalogueItemVariantQuantity(
                        value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                        unit = CatalogueItemVariantUnit.PerUnit.Piece,
                      ),
                      perUnit = None,
                      unitSize = None,
                    )
                  ),
                  sellingUnitInferred = true,
                  extractionNotes = List("Source label is partly obscured"),
                )
              ),
              extractionNotes = List.empty,
            ),
            CatalogueItemAIExtract(
              name = None,
              description = None,
              variants = List(
                CatalogueItemVariantAIExtract(
                  sku = None,
                  label = Some(CatalogueItemVariantLabel.assume("Blue / Large")),
                  description = None,
                  attributes = List.empty,
                  price = Some(
                    CatalogueItemVariantPriceAIExtract(
                      amount = PriceAmount.assume(BigDecimal("14.00")),
                      currency = None,
                      currencyInferred = false,
                    )
                  ),
                  sellingUnit = None,
                  sellingUnitInferred = false,
                  extractionNotes = List.empty,
                )
              ),
              extractionNotes = List("No item name is shown"),
            ),
          ),
          emptyEntryRows = List.empty,
          unidentifiedEntryRows = List.empty,
          extractionNotes = List("Image text was clear"),
        )

        val requestBody      = requestBodies(wiremockClient).loneElement
        val jsonSchemaCursor = requestBody.hcursor
          .downField("text")
          .downField("format")

        jsonSchemaCursor.get[Boolean]("strict").value shouldBe true

        val responseSchema   = jsonSchemaCursor.downField("schema").focus.value
        val attributesSchema = findPropertySchema(responseSchema, "attributes").value
        attributesSchema.hcursor.get[String]("type").value shouldBe "array"

        val attributeEntrySchema         = attributesSchema.hcursor.downField("items").focus.value
        val attributeEntrySchemaResolved =
          attributeEntrySchema.hcursor.get[String]("$ref").toOption.fold(attributeEntrySchema) { schemaReference =>
            schemaReference should startWith("#/$defs/")
            responseSchema.hcursor.downField("$defs").downField(schemaReference.stripPrefix("#/$defs/")).focus.value
          }
        attributeEntrySchemaResolved.hcursor.get[Boolean]("additionalProperties").value shouldBe false
        attributeEntrySchemaResolved.hcursor.get[Set[String]]("required").value shouldBe Set("name", "value")
        attributeEntrySchemaResolved.hcursor
          .downField("properties")
          .downField("name")
          .get[String]("type")
          .value shouldBe "string"
        attributeEntrySchemaResolved.hcursor
          .downField("properties")
          .downField("value")
          .get[String]("type")
          .value shouldBe "string"

        val schemaDefinitionsCursor  = responseSchema.hcursor.downField("$defs")
        val itemSchemaCursor         = schemaDefinitionsCursor.downField("CatalogueItemAIExtract")
        val variantSchemaCursor      = schemaDefinitionsCursor.downField("CatalogueItemVariantAIExtract")
        val variantPriceSchemaCursor = schemaDefinitionsCursor.downField("CatalogueItemVariantPriceAIExtract")
        val nullSchema               = Json.obj("type" -> Json.fromString("null"))

        itemSchemaCursor.downField("properties").downField("name").get[List[String]]("type").value shouldBe List(
          "string",
          "null",
        )
        variantSchemaCursor.downField("properties").downField("sellingUnit").get[List[Json]]("anyOf").value should
          contain(nullSchema)
        variantPriceSchemaCursor.downField("properties").downField("currency").get[List[Json]]("anyOf").value should
          contain(nullSchema)
        variantSchemaCursor.get[List[String]]("required").value should contain("sellingUnitInferred")
        variantSchemaCursor
          .downField("properties")
          .downField("sellingUnitInferred")
          .get[String]("type")
          .value shouldBe "boolean"
        variantPriceSchemaCursor.get[List[String]]("required").value should contain("currencyInferred")
        variantPriceSchemaCursor
          .downField("properties")
          .downField("currencyInferred")
          .get[String]("type")
          .value shouldBe "boolean"
      }

      "send processing guidance after the image source content" in withContext { context =>
        import context.*

        val instructions           = "AI_CLIENT_SPEC_SUCCESS"
        val processingMessage      = "Omit optional prices"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val extractedTestResult = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            instructions,
            processingMessageOpt = Some(processingMessage),
          )
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("extracted-value")

        val imageInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type"      -> Json.fromString("input_image"),
              "detail"    -> Json.fromString("high"),
              "image_url" -> Json.fromString(
                s"data:${SupportedMediaType.JPEG.mimes.head};base64,${Base64.getEncoder.encodeToString(imageBytes)}"
              ),
            )
          ),
        )
        val processingMessageInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type" -> Json.fromString("input_text"),
              "text" -> Json.fromString(processingMessage),
            )
          ),
        )
        val responseSchemaExpected =
          SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[ExtractedTestResult]].schema)
        requestBodies(wiremockClient).loneElement shouldBe Json.obj(
          "model"        -> Json.fromString(aiClientDataExtractionConfig.imageModel),
          "instructions" -> Json.fromString(instructions),
          "input"        -> Json.arr(imageInputMessage, processingMessageInputMessage),
          "reasoning"    -> Json.obj("effort" -> Json.fromString(aiClientDataExtractionConfig.imageReasoningEffort)),
          "store"        -> Json.False,
          "text"         -> Json.obj(
            "format" -> Json.obj(
              "type"   -> Json.fromString("json_schema"),
              "name"   -> Json.fromString("ai_client_response"),
              "strict" -> Json.True,
              "schema" -> responseSchemaExpected,
            )
          ),
        )
      }

      "successfully retry a rate-limited request and eventually decode the response" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val extractedTestResult = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_RETRY_SUCCESS",
          )
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("retried-value")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/responses"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the image path cannot be read before sending" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val unreadableImagePath = FileScannedPath(Files.createTempDirectory("ai-client-spec-missing-image-"))

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            unreadableImagePath,
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_PATH_READ_FAILURE",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Failed to read image for AI extraction"

        val extractFromImageRequestMappings = wiremockClient.requestsDetails.zioValue

        extractFromImageRequestMappings shouldBe Seq.empty
      }

      "fail with an UnexpectedError when the AI service returns an error" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_ERROR",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/responses"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service rejects the request after one attempt" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromImage[ExtractedTestResult](
              imageScannedPath(),
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_BAD_REQUEST",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val extractFromImageRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromImageRequestMappings.size shouldBe 1
          extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
          extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/responses"
          extractFromImageRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI connection resets after three attempts" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_CONNECTION_RESET",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/responses"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when each response times out after three attempts" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigTimeout =
          aiClientDataExtractionConfig.copy(requestTimeout = Duration.fromMillis(100))

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigTimeout),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_TIMEOUT",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/responses"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service returns undecodable structured-output JSON" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromImage[ExtractedTestResult](
              imageScannedPath(),
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_MALFORMED",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val extractFromImageRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromImageRequestMappings.size shouldBe 1
          extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
          extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/responses"
          extractFromImageRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI response does not match the expected shape" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromImage[ExtractedTestResult](
            imageScannedPath(),
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_UNEXPECTED_SHAPE",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message should startWith("Failed to parse AI response")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 1
        extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
        extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/responses"
        extractFromImageRequestMappings(0).count shouldBe 1
      }
    }

    "extractFromPdf" should {
      "successfully extract a structured response from a PDF sent as one file input" in withContext { context =>
        import context.*

        val instructions        = "AI_CLIENT_SPEC_PDF_SUCCESS"
        val pdfFileNameDeclared = "catalogue-price-list.pdf"

        aiClientDataExtractionConfig.pdfModel shouldNot equal(aiClientDataExtractionConfig.imageModel)
        aiClientDataExtractionConfig.pdfReasoningEffort shouldNot equal(
          aiClientDataExtractionConfig.imageReasoningEffort
        )

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val catalogueFileAIExtract = aiClientDataExtraction
          .extractFromPdf[CatalogueFileAIExtract](
            pdfScannedPath(),
            pdfFileNameDeclared,
            instructions,
          )
          .zioValue

        catalogueFileAIExtract shouldBe CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(CatalogueItemName.assume("Bolt")),
              description = None,
              variants = List(
                CatalogueItemVariantAIExtract(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = List(
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Color"),
                      CatalogueItemVariantAttributeValue.assume("Silver"),
                    )
                  ),
                  price = None,
                  sellingUnit = Some(
                    CatalogueItemVariantSaleUnit(
                      quantity = CatalogueItemVariantQuantity(
                        value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                        unit = CatalogueItemVariantUnit.PerUnit.Piece,
                      ),
                      perUnit = None,
                      unitSize = None,
                    )
                  ),
                  sellingUnitInferred = false,
                  extractionNotes = List.empty,
                )
              ),
              extractionNotes = List.empty,
            )
          ),
          emptyEntryRows = List.empty,
          unidentifiedEntryRows = List.empty,
          extractionNotes = List("The document was read in page order"),
        )

        val extractFromPdfRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromPdfRequestMappings.size shouldBe 1
        extractFromPdfRequestMappings(0).mapping.method shouldBe "POST"
        extractFromPdfRequestMappings(0).mapping.url shouldBe "/v1/responses"
        extractFromPdfRequestMappings(0).count shouldBe 1

        val pdfInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type"      -> Json.fromString("input_file"),
              "file_data" -> Json.fromString(
                s"data:${SupportedMediaType.PDF.mimes.head};base64,${Base64.getEncoder.encodeToString(pdfBytes)}"
              ),
              "filename" -> Json.fromString(pdfFileNameDeclared),
            )
          ),
        )
        val responseSchemaExpected =
          SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[CatalogueFileAIExtract]].schema)
        requestBodies(wiremockClient).loneElement shouldBe Json.obj(
          "model"        -> Json.fromString(aiClientDataExtractionConfig.pdfModel),
          "instructions" -> Json.fromString(instructions),
          "input"        -> Json.arr(pdfInputMessage),
          "reasoning"    -> Json.obj("effort" -> Json.fromString(aiClientDataExtractionConfig.pdfReasoningEffort)),
          "store"        -> Json.False,
          "text"         -> Json.obj(
            "format" -> Json.obj(
              "type"   -> Json.fromString("json_schema"),
              "name"   -> Json.fromString("ai_client_response"),
              "strict" -> Json.True,
              "schema" -> responseSchemaExpected,
            )
          ),
        )
      }

      "send processing guidance as a second user input after the PDF" in withContext { context =>
        import context.*

        val instructions           = "AI_CLIENT_SPEC_PDF_SUCCESS"
        val processingMessage      = "Omit optional prices"
        val pdfFileNameDeclared    = "supplier-price-list.pdf"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val catalogueFileAIExtract = aiClientDataExtraction
          .extractFromPdf[CatalogueFileAIExtract](
            pdfScannedPath(),
            pdfFileNameDeclared,
            instructions,
            processingMessageOpt = Some(processingMessage),
          )
          .zioValue

        catalogueFileAIExtract shouldBe CatalogueFileAIExtract(
          entriesIdentified = 1L,
          entriesProcessed = 1L,
          candidates = List(
            CatalogueItemAIExtract(
              name = Some(CatalogueItemName.assume("Bolt")),
              description = None,
              variants = List(
                CatalogueItemVariantAIExtract(
                  sku = Some(CatalogueItemVariantSku.assume("BOLT-1")),
                  label = None,
                  description = None,
                  attributes = List(
                    CatalogueItemVariantAttributesAIExtract(
                      CatalogueItemVariantAttributeName.assume("Color"),
                      CatalogueItemVariantAttributeValue.assume("Silver"),
                    )
                  ),
                  price = None,
                  sellingUnit = Some(
                    CatalogueItemVariantSaleUnit(
                      quantity = CatalogueItemVariantQuantity(
                        value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                        unit = CatalogueItemVariantUnit.PerUnit.Piece,
                      ),
                      perUnit = None,
                      unitSize = None,
                    )
                  ),
                  sellingUnitInferred = false,
                  extractionNotes = List.empty,
                )
              ),
              extractionNotes = List.empty,
            )
          ),
          emptyEntryRows = List.empty,
          unidentifiedEntryRows = List.empty,
          extractionNotes = List("The document was read in page order"),
        )

        val pdfInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type"      -> Json.fromString("input_file"),
              "file_data" -> Json.fromString(
                s"data:${SupportedMediaType.PDF.mimes.head};base64,${Base64.getEncoder.encodeToString(pdfBytes)}"
              ),
              "filename" -> Json.fromString(pdfFileNameDeclared),
            )
          ),
        )
        val processingMessageInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type" -> Json.fromString("input_text"),
              "text" -> Json.fromString(processingMessage),
            )
          ),
        )
        val responseSchemaExpected =
          SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[CatalogueFileAIExtract]].schema)
        requestBodies(wiremockClient).loneElement shouldBe Json.obj(
          "model"        -> Json.fromString(aiClientDataExtractionConfig.pdfModel),
          "instructions" -> Json.fromString(instructions),
          "input"        -> Json.arr(pdfInputMessage, processingMessageInputMessage),
          "reasoning"    -> Json.obj("effort" -> Json.fromString(aiClientDataExtractionConfig.pdfReasoningEffort)),
          "store"        -> Json.False,
          "text"         -> Json.obj(
            "format" -> Json.obj(
              "type"   -> Json.fromString("json_schema"),
              "name"   -> Json.fromString("ai_client_response"),
              "strict" -> Json.True,
              "schema" -> responseSchemaExpected,
            )
          ),
        )
      }

      "successfully retry a server error and eventually decode the response" in withContext { context =>
        import context.*

        val pdfFileNameDeclared    = "catalogue.pdf"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val extractedTestResult = aiClientDataExtraction
          .extractFromPdf[ExtractedTestResult](
            pdfScannedPath(),
            pdfFileNameDeclared,
            "AI_CLIENT_SPEC_PDF_RETRY_SUCCESS",
          )
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("retried-value")

        val extractFromPdfRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromPdfRequestMappings.size shouldBe 3
        extractFromPdfRequestMappings.foreach { extractFromPdfRequestMapping =>
          extractFromPdfRequestMapping.mapping.method shouldBe "POST"
          extractFromPdfRequestMapping.mapping.url shouldBe "/v1/responses"
          extractFromPdfRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the PDF path cannot be read before sending" in withContext { context =>
        import context.*

        val pdfFileNameDeclared    = "catalogue.pdf"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val unreadablePdfPath = FileScannedPath(Files.createTempDirectory("ai-client-spec-missing-pdf-"))

        val serviceError = aiClientDataExtraction
          .extractFromPdf[ExtractedTestResult](
            unreadablePdfPath,
            pdfFileNameDeclared,
            "AI_CLIENT_SPEC_PDF_PATH_READ_FAILURE",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Failed to read PDF for AI extraction"

        val extractFromPdfRequestMappings = wiremockClient.requestsDetails.zioValue

        extractFromPdfRequestMappings shouldBe Seq.empty
      }

      "fail with an UnexpectedError when the AI service rejects the request after one attempt" in withContext {
        context =>
          import context.*

          val pdfFileNameDeclared    = "catalogue.pdf"
          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromPdf[ExtractedTestResult](
              pdfScannedPath(),
              pdfFileNameDeclared,
              "AI_CLIENT_SPEC_PDF_BAD_REQUEST",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val extractFromPdfRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromPdfRequestMappings.size shouldBe 1
          extractFromPdfRequestMappings(0).mapping.method shouldBe "POST"
          extractFromPdfRequestMappings(0).mapping.url shouldBe "/v1/responses"
          extractFromPdfRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI service keeps returning a server error after three attempts" in withContext {
        context =>
          import context.*

          val pdfFileNameDeclared    = "catalogue.pdf"
          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromPdf[ExtractedTestResult](
              pdfScannedPath(),
              pdfFileNameDeclared,
              "AI_CLIENT_SPEC_PDF_ERROR",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val extractFromPdfRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromPdfRequestMappings.size shouldBe 3
          extractFromPdfRequestMappings.foreach { extractFromPdfRequestMapping =>
            extractFromPdfRequestMapping.mapping.method shouldBe "POST"
            extractFromPdfRequestMapping.mapping.url shouldBe "/v1/responses"
            extractFromPdfRequestMapping.count shouldBe 3
          }
      }

      "fail with an UnexpectedError when each response times out after three attempts" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigTimeout =
          aiClientDataExtractionConfig.copy(requestTimeout = Duration.fromMillis(100))

        val pdfFileNameDeclared    = "catalogue.pdf"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigTimeout),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromPdf[ExtractedTestResult](
            pdfScannedPath(),
            pdfFileNameDeclared,
            "AI_CLIENT_SPEC_PDF_TIMEOUT",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromPdfRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromPdfRequestMappings.size shouldBe 3
        extractFromPdfRequestMappings.foreach { extractFromPdfRequestMapping =>
          extractFromPdfRequestMapping.mapping.method shouldBe "POST"
          extractFromPdfRequestMapping.mapping.url shouldBe "/v1/responses"
          extractFromPdfRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service returns undecodable structured-output JSON" in withContext {
        context =>
          import context.*

          val pdfFileNameDeclared    = "catalogue.pdf"
          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromPdf[ExtractedTestResult](
              pdfScannedPath(),
              pdfFileNameDeclared,
              "AI_CLIENT_SPEC_PDF_MALFORMED",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val extractFromPdfRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromPdfRequestMappings.size shouldBe 1
          extractFromPdfRequestMappings(0).mapping.method shouldBe "POST"
          extractFromPdfRequestMappings(0).mapping.url shouldBe "/v1/responses"
          extractFromPdfRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError without retrying when the AI refuses to answer" in withContext { context =>
        import context.*

        val pdfFileNameDeclared    = "catalogue.pdf"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromPdf[ExtractedTestResult](
            pdfScannedPath(),
            pdfFileNameDeclared,
            "AI_CLIENT_SPEC_PDF_REFUSAL",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message should startWith("Failed to parse AI response")

        val extractFromPdfRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromPdfRequestMappings.size shouldBe 1
        extractFromPdfRequestMappings(0).mapping.method shouldBe "POST"
        extractFromPdfRequestMappings(0).mapping.url shouldBe "/v1/responses"
        extractFromPdfRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError without retrying when the AI response is not completed" in withContext { context =>
        import context.*

        val pdfFileNameDeclared    = "catalogue.pdf"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .extractFromPdf[ExtractedTestResult](
            pdfScannedPath(),
            pdfFileNameDeclared,
            "AI_CLIENT_SPEC_PDF_INCOMPLETE",
          )
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message should startWith("Failed to parse AI response")

        val extractFromPdfRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromPdfRequestMappings.size shouldBe 1
        extractFromPdfRequestMappings(0).mapping.method shouldBe "POST"
        extractFromPdfRequestMappings(0).mapping.url shouldBe "/v1/responses"
        extractFromPdfRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError without retrying when the AI response has no message output" in withContext {
        context =>
          import context.*

          val pdfFileNameDeclared    = "catalogue.pdf"
          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .extractFromPdf[ExtractedTestResult](
              pdfScannedPath(),
              pdfFileNameDeclared,
              "AI_CLIENT_SPEC_PDF_NO_MESSAGE",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val extractFromPdfRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromPdfRequestMappings.size shouldBe 1
          extractFromPdfRequestMappings(0).mapping.method shouldBe "POST"
          extractFromPdfRequestMappings(0).mapping.url shouldBe "/v1/responses"
          extractFromPdfRequestMappings(0).count shouldBe 1
      }
    }

    "extractFromCsv" should {
      "decode one AI result per batch, in batch order" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            (1 to 101).map(index => s"Customer $index,customer$index@example.com\n").mkString

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(3)(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 3
        extractFromCsvRequestMappings.map(_.mapping.method).distinct shouldBe Seq("POST")
        extractFromCsvRequestMappings.map(_.mapping.url).distinct shouldBe Seq("/v1/responses")
        extractFromCsvRequestMappings.map(_.count).distinct shouldBe Seq(3)
      }

      "split records across multiple AI requests once they exceed the batch size limit" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigSmallBatches = aiClientDataExtractionConfig.copy(csvBatchMaxDataRows = 10)

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigSmallBatches),
            SttpTestBackend.live,
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            (1 to 25).map(index => s"Customer $index,customer$index@example.com\n").mkString

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(3)(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 3
        extractFromCsvRequestMappings.map(_.mapping.method).distinct shouldBe Seq("POST")
        extractFromCsvRequestMappings.map(_.mapping.url).distinct shouldBe Seq("/v1/responses")
        extractFromCsvRequestMappings.map(_.count).distinct shouldBe Seq(3)
      }

      "send the same processing guidance after every source batch" in withContext { context =>
        import context.*

        val csvBatchMaxDataRows                      = 2
        val csvBatchCountExpected                    = 2
        val aiClientDataExtractionConfigSmallBatches =
          aiClientDataExtractionConfig.copy(csvBatchMaxDataRows = csvBatchMaxDataRows)
        val instructions           = "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS"
        val processingMessage      = "Omit optional prices"
        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigSmallBatches),
            SttpTestBackend.live,
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            "Alice,alice@example.com\n" +
            "Bob,bob@example.com\n" +
            "Carol,carol@example.com\n"

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              instructions,
              processingMessageOpt = Some(processingMessage),
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(csvBatchCountExpected)(
          ExtractedTestResult("batch-result")
        )

        val csvBatchInputMessageFirst = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type" -> Json.fromString("input_text"),
              "text" -> Json.fromString(
                "Row Number,Full Name,Email\r\n2,Alice,alice@example.com\r\n3,Bob,bob@example.com\r\n"
              ),
            )
          ),
        )
        val csvBatchInputMessageSecond = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type" -> Json.fromString("input_text"),
              "text" -> Json.fromString("Row Number,Full Name,Email\r\n4,Carol,carol@example.com\r\n"),
            )
          ),
        )
        val processingMessageInputMessage = Json.obj(
          "type"    -> Json.fromString("message"),
          "role"    -> Json.fromString("user"),
          "content" -> Json.arr(
            Json.obj(
              "type" -> Json.fromString("input_text"),
              "text" -> Json.fromString(processingMessage),
            )
          ),
        )
        val responseSchemaExpected =
          SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[ExtractedTestResult]].schema)
        val csvBatchRequestBodyFirstExpected = Json.obj(
          "model"        -> Json.fromString(aiClientDataExtractionConfigSmallBatches.csvModel),
          "instructions" -> Json.fromString(instructions),
          "input"        -> Json.arr(csvBatchInputMessageFirst, processingMessageInputMessage),
          "reasoning"    -> Json.obj(
            "effort" -> Json.fromString(aiClientDataExtractionConfigSmallBatches.csvReasoningEffort)
          ),
          "store" -> Json.False,
          "text"  -> Json.obj(
            "format" -> Json.obj(
              "type"   -> Json.fromString("json_schema"),
              "name"   -> Json.fromString("ai_client_response"),
              "strict" -> Json.True,
              "schema" -> responseSchemaExpected,
            )
          ),
        )
        val csvBatchRequestBodySecondExpected = csvBatchRequestBodyFirstExpected.deepMerge(
          Json.obj("input" -> Json.arr(csvBatchInputMessageSecond, processingMessageInputMessage))
        )
        val csvBatchRequestBodies = requestBodies(wiremockClient)

        csvBatchRequestBodies.size shouldBe csvBatchCountExpected
        csvBatchRequestBodies.toSet shouldBe Set(csvBatchRequestBodyFirstExpected, csvBatchRequestBodySecondExpected)
      }

      "keep a blank row in its batch instead of excluding it before batching" in withContext { context =>
        import context.*

        val aiClientDataExtractionConfigSmallBatches = aiClientDataExtractionConfig.copy(csvBatchMaxDataRows = 2)

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfigSmallBatches),
            SttpTestBackend.live,
          )
          .zioValue

        val csvText =
          "Full Name,Email\n" +
            "Alice,alice@example.com\n" +
            ",\n" +
            "Bob,bob@example.com\n"

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List.fill(2)(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 2
        extractFromCsvRequestMappings.map(_.mapping.method).distinct shouldBe Seq("POST")
        extractFromCsvRequestMappings.map(_.mapping.url).distinct shouldBe Seq("/v1/responses")
        extractFromCsvRequestMappings.map(_.count).distinct shouldBe Seq(2)
      }

      "send each row's real file position in a leading Row Number column, blank rows included" in withContext {
        context =>
          import context.*

          val instructions           = "AI_CLIENT_SPEC_CSV_ROW_NUMBER_SUCCESS"
          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val csvText =
            "Full Name,Email\n" +
              "Alice,alice@example.com\n" +
              ",\n" +
              "Bob,bob@example.com\n"

          val extractFromCsvResults = ZIO
            .scoped(
              aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
                csvRecordStream(csvText),
                instructions,
              )
            )
            .zioValue

          extractFromCsvResults.toChunk.toList shouldBe List(ExtractedTestResult("row-number-result"))

          val extractFromCsvRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromCsvRequestMappings.size shouldBe 1
          extractFromCsvRequestMappings(0).mapping.method shouldBe "POST"
          extractFromCsvRequestMappings(0).mapping.url shouldBe "/v1/responses"
          extractFromCsvRequestMappings(0).count shouldBe 1

          val csvBatchInputMessage = Json.obj(
            "type"    -> Json.fromString("message"),
            "role"    -> Json.fromString("user"),
            "content" -> Json.arr(
              Json.obj(
                "type" -> Json.fromString("input_text"),
                "text" -> Json.fromString(
                  "Row Number,Full Name,Email\r\n2,Alice,alice@example.com\r\n3,,\r\n4,Bob,bob@example.com\r\n"
                ),
              )
            ),
          )
          val responseSchemaExpected =
            SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[ExtractedTestResult]].schema)
          requestBodies(wiremockClient).loneElement shouldBe Json.obj(
            "model"        -> Json.fromString(aiClientDataExtractionConfig.csvModel),
            "instructions" -> Json.fromString(instructions),
            "input"        -> Json.arr(csvBatchInputMessage),
            "reasoning"    -> Json.obj("effort" -> Json.fromString(aiClientDataExtractionConfig.csvReasoningEffort)),
            "store"        -> Json.False,
            "text"         -> Json.obj(
              "format" -> Json.obj(
                "type"   -> Json.fromString("json_schema"),
                "name"   -> Json.fromString("ai_client_response"),
                "strict" -> Json.True,
                "schema" -> responseSchemaExpected,
              )
            ),
          )
      }

      "send exactly one batch for a CSV record stream with only a header row" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val csvText = "Full Name,Email\n"

        val extractFromCsvResults = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
              csvRecordStream(csvText),
              "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
            )
          )
          .zioValue

        extractFromCsvResults.toChunk.toList shouldBe List(ExtractedTestResult("batch-result"))

        val extractFromCsvRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromCsvRequestMappings.size shouldBe 1
        extractFromCsvRequestMappings(0).mapping.method shouldBe "POST"
        extractFromCsvRequestMappings(0).mapping.url shouldBe "/v1/responses"
        extractFromCsvRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the CSV record stream fails before any batch is sent" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceErrorStreamFailure =
            ServiceError.InternalServerError.UnexpectedError("CSV record stream failed upstream", None)

          val serviceError = ZIO
            .scoped(
              aiClientDataExtraction.extractFromCsv[ExtractedTestResult](
                ZStream.fail(serviceErrorStreamFailure),
                "AI_CLIENT_SPEC_CSV_GENERIC_SUCCESS",
              )
            )
            .zioError

          serviceError shouldBe serviceErrorStreamFailure

          wiremockClient.requestsDetails.zioValue shouldBe Seq.empty
      }
    }

    "noteCompaction" should {
      "send the notes as one escaped JSON array of strings to the note compaction model with a strict schema and decode the compacted notes" in withContext {
        context =>
          import context.*

          val instructions = "Combine these notes"
          val notes        = List(
            "NOTE_COMPACTION_SPEC_SUCCESS",
            "Two rows were blank",
            "Left out row 7, the heading \"WHOLESALE CLIENTS\",\nbecause nothing is beside it",
          )
          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val noteCompactionOutput = aiClientDataExtraction.noteCompaction(notes, instructions).zioValue

          noteCompactionOutput shouldBe NoteCompactionOutput(
            notes = List("Two rows were blank", "One row had no readable name")
          )

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe 1
          noteCompactionRequestMappings(0).mapping.method shouldBe "POST"
          noteCompactionRequestMappings(0).mapping.url shouldBe "/v1/responses"
          noteCompactionRequestMappings(0).count shouldBe 1

          aiClientDataExtractionConfig.noteCompactionModel shouldNot equal(aiClientDataExtractionConfig.csvModel)
          aiClientDataExtractionConfig.noteCompactionModel shouldNot equal(aiClientDataExtractionConfig.imageModel)

          val notesInputMessage = Json.obj(
            "type"    -> Json.fromString("message"),
            "role"    -> Json.fromString("user"),
            "content" -> Json.arr(
              Json.obj(
                "type" -> Json.fromString("input_text"),
                "text" -> Json.fromString(
                  """["NOTE_COMPACTION_SPEC_SUCCESS","Two rows were blank","Left out row 7, the heading \"WHOLESALE CLIENTS\",\nbecause nothing is beside it"]"""
                ),
              )
            ),
          )
          val responseSchemaExpected =
            SchemaSupport.normalizeForStrict(summon[OpenAIJsonSchema[NoteCompactionOutput]].schema)
          val requestBody = requestBodies(wiremockClient).loneElement

          requestBody shouldBe Json.obj(
            "model"        -> Json.fromString(aiClientDataExtractionConfig.noteCompactionModel),
            "instructions" -> Json.fromString(instructions),
            "input"        -> Json.arr(notesInputMessage),
            "reasoning"    -> Json.obj(
              "effort" -> Json.fromString(aiClientDataExtractionConfig.noteCompactionReasoningEffort)
            ),
            "store" -> Json.False,
            "text"  -> Json.obj(
              "format" -> Json.obj(
                "type"   -> Json.fromString("json_schema"),
                "name"   -> Json.fromString("ai_client_response"),
                "strict" -> Json.True,
                "schema" -> responseSchemaExpected,
              )
            ),
          )

          val responseSchemaCursor = requestBody.hcursor.downField("text").downField("format").downField("schema")
          responseSchemaCursor.get[Boolean]("additionalProperties").value shouldBe false
          responseSchemaCursor.get[Set[String]]("required").value shouldBe Set("notes")
          responseSchemaCursor.downField("properties").downField("notes").get[String]("type").value shouldBe "array"
          responseSchemaCursor
            .downField("properties")
            .downField("notes")
            .downField("items")
            .get[String]("type")
            .value shouldBe "string"
      }

      "fail with an UnexpectedError when the AI service returns an error" in withContext { context =>
        import context.*

        val aiClientDataExtraction = ZIO
          .service[AIClientDataExtraction]
          .provide(
            AIClientDataExtraction.live,
            ZLayer.succeed(aiClientDataExtractionConfig),
            SttpTestBackend.live,
          )
          .zioValue

        val serviceError = aiClientDataExtraction
          .noteCompaction(List("NOTE_COMPACTION_SPEC_ERROR"), "Combine these notes")
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val noteCompactionRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        noteCompactionRequestMappings.size shouldBe aiClientDataExtractionConfig.sendMaxRetries + 1
        noteCompactionRequestMappings.foreach { noteCompactionRequestMapping =>
          noteCompactionRequestMapping.mapping.method shouldBe "POST"
          noteCompactionRequestMapping.mapping.url shouldBe "/v1/responses"
          noteCompactionRequestMapping.count shouldBe aiClientDataExtractionConfig.sendMaxRetries + 1
        }
      }

      "fail with an UnexpectedError when the AI service rejects the request after one attempt" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .noteCompaction(List("NOTE_COMPACTION_SPEC_BAD_REQUEST"), "Combine these notes")
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe 1
          noteCompactionRequestMappings(0).mapping.method shouldBe "POST"
          noteCompactionRequestMappings(0).mapping.url shouldBe "/v1/responses"
          noteCompactionRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when each response exceeds the note compaction timeout after three attempts" in withContext {
        context =>
          import context.*

          val aiClientDataExtractionConfigTimeout =
            aiClientDataExtractionConfig.copy(noteCompactionRequestTimeout = Duration.fromMillis(100))

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfigTimeout),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .noteCompaction(List("AI_CLIENT_SPEC_TIMEOUT"), "Combine these notes")
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message shouldBe "Unable to send message to AI"

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe aiClientDataExtractionConfigTimeout.sendMaxRetries + 1
          noteCompactionRequestMappings.foreach { noteCompactionRequestMapping =>
            noteCompactionRequestMapping.mapping.method shouldBe "POST"
            noteCompactionRequestMapping.mapping.url shouldBe "/v1/responses"
            noteCompactionRequestMapping.count shouldBe aiClientDataExtractionConfigTimeout.sendMaxRetries + 1
          }
      }

      "fail with an UnexpectedError when the AI service returns undecodable structured-output JSON" in withContext {
        context =>
          import context.*

          val aiClientDataExtraction = ZIO
            .service[AIClientDataExtraction]
            .provide(
              AIClientDataExtraction.live,
              ZLayer.succeed(aiClientDataExtractionConfig),
              SttpTestBackend.live,
            )
            .zioValue

          val serviceError = aiClientDataExtraction
            .noteCompaction(List("NOTE_COMPACTION_SPEC_MALFORMED"), "Combine these notes")
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val noteCompactionRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          noteCompactionRequestMappings.size shouldBe 1
          noteCompactionRequestMappings(0).mapping.method shouldBe "POST"
          noteCompactionRequestMappings(0).mapping.url shouldBe "/v1/responses"
          noteCompactionRequestMappings(0).count shouldBe 1
      }
    }
  }
}
