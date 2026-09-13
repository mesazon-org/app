package io.mesazon.gateway.it

import com.dimafeng.testcontainers.ExposedService
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.config.AIClientConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.extractCustomersResponseCodec
import io.mesazon.gateway.json.{ai, OpenAIJsonSchema}
import io.mesazon.gateway.utils.FileByteStreamScanned
import io.mesazon.testkit.base.*
import io.mesazon.wiremock.WiremockClient
import io.mesazon.wiremock.WiremockClient.WiremockClientConfig
import sttp.client4.httpclient.zio.HttpClientZioBackend
import sttp.model.StatusCode
import sttp.tapir.Schema
import zio.*
import zio.stream.*

class AIClientSpec extends ZWordSpecBase, DockerComposeBase {

  override def dockerComposeFile: String = "./src/test/resources/compose/wiremock.yaml"

  override def exposedServices: Set[ExposedService] = WiremockClient.ExposedServices

  private lazy val extractedTestResultSchema: Schema[ExtractedTestResult] = Schema.derived[ExtractedTestResult]

  case class ExtractedTestResult(value: String)
  case class Context(aiClientConfig: AIClientConfig, wiremockClient: WiremockClient)

  given extractedTestResultOpenAIJsonSchema: OpenAIJsonSchema[ExtractedTestResult] =
    ai.fromTapir(extractedTestResultSchema)
  given JsonValueCodec[ExtractedTestResult] = JsonCodecMaker.make[ExtractedTestResult]

  def withContext[A](f: Context => A): A = withContainers { container =>
    val wiremockClientConfig = WiremockClientConfig.from(container)
    val wiremockClient       = ZIO
      .service[WiremockClient]
      .provide(
        WiremockClient.live,
        ZLayer.succeed(wiremockClientConfig),
        HttpClientZioBackend.layer(),
      )
      .zioValue

    val aiClientConfig = AIClientConfig(
      scheme = "http",
      host = wiremockClientConfig.host,
      port = wiremockClientConfig.port,
      apiKey = "test-api-key",
      requestTimeout = Duration.fromMillis(100),
      sendMaxRetries = 2,
      sendRetryDelay = Duration.fromMillis(10),
    )

    f(Context(aiClientConfig, wiremockClient))
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

  "AIClient" when {
    "extractFromImage" should {
      "successfully extract a structured response from a photo" in withContext { context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val extractedTestResult = aiClient
          .extractFromImage[ExtractedTestResult](imageByteStream, SupportedMediaType.JPEG, "AI_CLIENT_SPEC_SUCCESS")
          .zioValue

        extractedTestResult shouldBe ExtractedTestResult("extracted-value")

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 1

        extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
        extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
        extractFromImageRequestMappings(0).count shouldBe 1
      }

      "successfully decode a candidate marked as a duplicate with populated extraction notes" in withContext {
        context =>
          import context.*

          val aiClient = ZIO
            .service[AIClient]
            .provide(
              AIClient.live,
              ZLayer.succeed(aiClientConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

          val extractCustomersResponse = aiClient
            .extractFromImage[ExtractCustomersResponse](
              imageByteStream,
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_DUPLICATE_NOTES",
            )
            .zioValue

          val extractCustomerIndividualDataExpected = ExtractCustomerIndividualData(
            candidate = ExtractCustomerIndividual(
              fullName = CustomerFullName.assume("John Smith"),
              emails = List.empty,
              phoneNumbers = List.empty,
              addressLine1 = None,
              addressLine2 = None,
              city = None,
              postalCode = None,
              country = None,
            ),
            isDuplicate = true,
            extractionNotes = Some("Phone number partially illegible"),
          )

          extractCustomersResponse shouldBe ExtractCustomersResponse(
            entriesIdentified = 1L,
            entriesProcessed = 1L,
            customerIndividualCandidates = List(extractCustomerIndividualDataExpected),
            customerBusinessCandidates = List.empty,
            unidentifiedEntriesSummary = None,
          )
      }

      "successfully decode a response with a populated unidentified-entries summary" in withContext { context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val extractCustomersResponse = aiClient
          .extractFromImage[ExtractCustomersResponse](
            imageByteStream,
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_UNIDENTIFIED_SUMMARY",
          )
          .zioValue

        val extractCustomerIndividualDataExpected = ExtractCustomerIndividualData(
          candidate = ExtractCustomerIndividual(
            fullName = CustomerFullName.assume("Alice Wong"),
            emails = List.empty,
            phoneNumbers = List.empty,
            addressLine1 = None,
            addressLine2 = None,
            city = None,
            postalCode = None,
            country = None,
          ),
          isDuplicate = false,
          extractionNotes = None,
        )

        extractCustomersResponse shouldBe ExtractCustomersResponse(
          entriesIdentified = 4L,
          entriesProcessed = 1L,
          customerIndividualCandidates = List(extractCustomerIndividualDataExpected),
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = Some("Could not read the last 3 entries"),
        )
      }

      "successfully decode an empty result when nothing is recognizable in the photo" in withContext { context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val extractCustomersResponse = aiClient
          .extractFromImage[ExtractCustomersResponse](
            imageByteStream,
            SupportedMediaType.JPEG,
            "AI_CLIENT_SPEC_EMPTY_RESULT",
          )
          .zioValue

        extractCustomersResponse shouldBe ExtractCustomersResponse(
          entriesIdentified = 0L,
          entriesProcessed = 0L,
          customerIndividualCandidates = List.empty,
          customerBusinessCandidates = List.empty,
          unidentifiedEntriesSummary = None,
        )
      }

      "fail with an UnexpectedError when the AI service returns an error" in withContext { context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val serviceError = aiClient
          .extractFromImage[ExtractedTestResult](imageByteStream, SupportedMediaType.JPEG, "AI_CLIENT_SPEC_ERROR")
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 3
        extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
          extractFromImageRequestMapping.mapping.method shouldBe "POST"
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service rejects the request after one attempt" in withContext {
        context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val serviceError = aiClient
          .extractFromImage[ExtractedTestResult](imageByteStream, SupportedMediaType.JPEG, "AI_CLIENT_SPEC_BAD_REQUEST")
          .zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError.message shouldBe "Unable to send message to AI"

        val extractFromImageRequestMappings =
          wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

        extractFromImageRequestMappings.size shouldBe 1
        extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
        extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
        extractFromImageRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI connection resets after three attempts" in withContext {
        context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val serviceError = aiClient
          .extractFromImage[ExtractedTestResult](
            imageByteStream,
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
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "retry a rate-limited request and eventually decode the response while reading the image once" in withContext {
        context =>
          import context.*

          val aiClient = ZIO
            .service[AIClient]
            .provide(
              AIClient.live,
              ZLayer.succeed(aiClientConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val imageReadCountRef = Ref.make(0).zioValue
          val imageByteStream   = FileByteStreamScanned(
            ZStream
              .fromZIO(imageReadCountRef.updateAndGet(_ + 1))
              .flatMap(_ => ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))
          )

          val extractedTestResult = aiClient
            .extractFromImage[ExtractedTestResult](
              imageByteStream,
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_RETRY_SUCCESS",
            )
            .zioValue

          extractedTestResult shouldBe ExtractedTestResult("retried-value")
          imageReadCountRef.refValue shouldBe 1

          val extractFromImageRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromImageRequestMappings.size shouldBe 3
          extractFromImageRequestMappings.foreach { extractFromImageRequestMapping =>
            extractFromImageRequestMapping.mapping.method shouldBe "POST"
            extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
            extractFromImageRequestMapping.count shouldBe 3
          }
      }

      "fail with an UnexpectedError when each response times out after three attempts" in withContext {
        context =>
        import context.*

        val aiClient = ZIO
          .service[AIClient]
          .provide(
            AIClient.live,
            ZLayer.succeed(aiClientConfig),
            HttpClientZioBackend.layer(),
          )
          .zioValue

        val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

        val serviceError = aiClient
          .extractFromImage[ExtractedTestResult](
            imageByteStream,
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
          extractFromImageRequestMapping.mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMapping.count shouldBe 3
        }
      }

      "fail with an UnexpectedError when the AI service returns undecodable structured-output JSON" in withContext {
        context =>
          import context.*

          val aiClient = ZIO
            .service[AIClient]
            .provide(
              AIClient.live,
              ZLayer.succeed(aiClientConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

          val serviceError = aiClient
            .extractFromImage[ExtractedTestResult](
              imageByteStream,
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
          extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMappings(0).count shouldBe 1
      }

      "fail with an UnexpectedError when the AI response violates a refined field constraint" in withContext {
        context =>
          import context.*

          val aiClient = ZIO
            .service[AIClient]
            .provide(
              AIClient.live,
              ZLayer.succeed(aiClientConfig),
              HttpClientZioBackend.layer(),
            )
            .zioValue

          val imageByteStream = FileByteStreamScanned(ZStream.fromIterable(Array[Byte](1, 2, 3, 4, 5)))

          val serviceError = aiClient
            .extractFromImage[ExtractCustomersResponse](
              imageByteStream,
              SupportedMediaType.JPEG,
              "AI_CLIENT_SPEC_INVALID_REFINED",
            )
            .zioError

          serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
          serviceError.message should startWith("Failed to parse AI response")

          val extractFromImageRequestMappings =
            wiremockClient.requestsDetails.zioValue.filter(_.count > 0).sortBy(_.lastCallDate)

          extractFromImageRequestMappings.size shouldBe 1
          extractFromImageRequestMappings(0).mapping.method shouldBe "POST"
          extractFromImageRequestMappings(0).mapping.url shouldBe "/v1/chat/completions"
          extractFromImageRequestMappings(0).count shouldBe 1
      }
    }
  }
}
