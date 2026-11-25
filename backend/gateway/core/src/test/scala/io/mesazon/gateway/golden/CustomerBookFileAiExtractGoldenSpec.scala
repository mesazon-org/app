package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import diffson.circe.*
import diffson.diff
import diffson.jsonpatch.lcsdiff.remembering.*
import diffson.lcs.{Lcs, Patience}
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.{addressTypeCodec, languageCodec}
import io.mesazon.gateway.service.AIInstructions
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{SttpTestBackend, ZWordSpecBase}
import zio.*

import java.nio.file.*

class CustomerBookFileAiExtractGoldenSpec extends ZWordSpecBase {

  private inline val AiClientDataExtractionEnvPrefix = "AI_CLIENT_DATA_EXTRACTION_"

  private inline val AiClientDataExtractionApiKeyEnvName = "AI_CLIENT_DATA_EXTRACTION_API_KEY"

  private inline val OpenAiScheme = "https"

  private inline val OpenAiHost = "api.openai.com"

  private inline val OpenAiPort = 443

  private inline val GoldenRequestTimeoutSeconds = 600

  private given lcsJson: Lcs[Json] = new Patience[Json]

  private final case class Context(
      aiClientDataExtraction: AIClientDataExtraction,
      spreadsheetTool: SpreadsheetTool,
  )

  private val apiKey =
    sys.env.getOrElse(
      AiClientDataExtractionApiKeyEnvName,
      "",
    )

  private val missingApiKeyMessage = s"Set $AiClientDataExtractionApiKeyEnvName to run this manually"

  private val assetDirectory =
    Paths.get("src/test/resources/assets-test/CustomerBookFileAiExtractGoldenSpec")

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CustomerBookFileAiExtractGoldenSpec")

  private val diffsonDirectory =
    Paths.get("src/test/resources/assets-diffson/CustomerBookFileAiExtractGoldenSpec")

  private val isProductDefaultSettings: Boolean =
    !sys.env.keys.exists(environmentVariableName =>
      environmentVariableName.startsWith(AiClientDataExtractionEnvPrefix) &&
        environmentVariableName != AiClientDataExtractionApiKeyEnvName
    )

  private val customerBookFileAIExtractCodecFieldComplete: JsonValueCodec[CustomerBookFileAIExtract] =
    JsonCodecMaker.make[CustomerBookFileAIExtract](
      CodecMakerConfig.withTransientNone(false).withTransientEmpty(false).withTransientDefault(false)
    )

  private def loadCustomerBookFileAIExtractGolden(goldenFileName: String): CustomerBookFileAIExtract = {
    val golden = goldenDirectory.resolve(goldenFileName)

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[CustomerBookFileAIExtract](Files.readString(golden))
  }

  private def withContext[A](f: Context => Task[A]): A =
    ZIO
      .serviceWithZIO[Context](f)
      .provide(
        ZLayer.fromFunction(Context.apply),
        AIClientDataExtraction.live,
        SpreadsheetTool.live,
        ZLayer.succeed(AppName("gateway-api")),
        AIClientDataExtractionConfig.live.map(environment =>
          environment.update[AIClientDataExtractionConfig](
            _.copy(
              scheme = OpenAiScheme,
              host = OpenAiHost,
              port = OpenAiPort,
              apiKey = apiKey,
              requestTimeout = Duration.fromSeconds(GoldenRequestTimeoutSeconds),
            )
          )
        ),
        SttpTestBackend.live,
      )
      .zioValue

  private def mergeCustomerBookFileAIExtracts(
      customerBookFileAIExtracts: NonEmptyChunk[CustomerBookFileAIExtract]
  ): CustomerBookFileAIExtract = {
    val customerBookFileAIExtractsList = customerBookFileAIExtracts.toList

    CustomerBookFileAIExtract(
      entriesIdentified = customerBookFileAIExtracts.map(_.entriesIdentified).sum,
      entriesProcessed = customerBookFileAIExtracts.map(_.entriesProcessed).sum,
      individualCandidates = customerBookFileAIExtractsList.flatMap(_.individualCandidates),
      businessCandidates = customerBookFileAIExtractsList.flatMap(_.businessCandidates),
      emptyEntryRowIndexes = customerBookFileAIExtractsList.flatMap(_.emptyEntryRowIndexes),
      unidentifiedEntryRowIndexes = customerBookFileAIExtractsList.flatMap(_.unidentifiedEntryRowIndexes),
      extractionNotes = customerBookFileAIExtractsList.flatMap(_.extractionNotes),
    )
  }

  private def customerBookFileAIExtractJson(customerBookFileAIExtract: CustomerBookFileAIExtract): Json =
    parse(writeToString(customerBookFileAIExtract)(using customerBookFileAIExtractCodecFieldComplete))
      .fold(parsingFailure => fail(s"Failed to parse the encoded answer: [${parsingFailure.message}]"), identity)

  private def compareWithGolden(
      goldenFileName: String,
      customerBookFileAIExtract: CustomerBookFileAIExtract,
      customerBookFileAIExtractGolden: CustomerBookFileAIExtract,
  ): Unit = {
    val jsonPatch = diff(
      customerBookFileAIExtractJson(customerBookFileAIExtractGolden),
      customerBookFileAIExtractJson(customerBookFileAIExtract),
    )
    val currentText = jsonPatch.asJson.spaces2
    val storedPath  = diffsonDirectory.resolve(goldenFileName)

    if (Files.exists(storedPath)) {
      info("Stored:\n" + Files.readString(storedPath))
      info("Current:\n" + currentText)
    } else if (isProductDefaultSettings) {
      Files.createDirectories(diffsonDirectory)
      Files.writeString(storedPath, currentText, StandardOpenOption.CREATE_NEW)
      info("Current:\n" + currentText)
      info(s"Stored patch written: ${storedPath.toAbsolutePath}")
    } else {
      info("Current:\n" + currentText)
      info("Nothing stored: the settings are not the product defaults")
    }
  }

  "AIClientDataExtraction" when {
    "extractFromImage" should {
      "match the golden answer for the contact-list picture image-1.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-1.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-1.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-2.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-2.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-2.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-3.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-3.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-3.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-4.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-4.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-4.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-5.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-5.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-5.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-6.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-6.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-6.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-7.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-7.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-7.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-8.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-8.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-8.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-9.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-9.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-9.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-10.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-10.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-10.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the contact-list picture image-1.png sent with its processing message" in withContext {
        context =>
          import context.*

          for {
            _ = assume(
              apiKey.nonEmpty,
              missingApiKeyMessage,
            )

            customerBookFileProcessingMessage = "omit phone numbers, keep everything else"

            goldenFileName                  = "image-1-omit-phone-numbers.json"
            customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

            customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
              FileScannedPath(assetDirectory.resolve("image-1.png")),
              SupportedMediaType.PNG,
              AIInstructions.extractCustomersFromImageInstructions,
              Some(customerBookFileProcessingMessage),
            )

            _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
          } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-11.jpeg" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-11.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-11.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-12.webp" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-12.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-12.webp")),
            SupportedMediaType.WEBP,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-13.png" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-13.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-13.png")),
            SupportedMediaType.PNG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated picture image-14.jpeg" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "image-14.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromImage[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-14.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCustomersFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }
    }

    "extractFromPdf" should {
      "match the golden answer for the typed PDF contact list pdf-1.pdf" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "pdf-1.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromPdf[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-1.pdf")),
            "pdf-1.pdf",
            AIInstructions.extractCustomersFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the scanned handwritten PDF contact list pdf-2.pdf" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "pdf-2.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromPdf[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-2.pdf")),
            "pdf-2.pdf",
            AIInstructions.extractCustomersFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no customers for the unrelated PDF pdf-3.pdf" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "pdf-3.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtract <- aiClientDataExtraction.extractFromPdf[CustomerBookFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-3.pdf")),
            "pdf-3.pdf",
            AIInstructions.extractCustomersFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }
    }

    "extractFromCsv" should {
      "match the golden answer for the spreadsheet spreadsheet-1.csv" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "spreadsheet-1.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-1.csv"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the spreadsheet spreadsheet-2.xls" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "spreadsheet-2.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-2.xls"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the spreadsheet spreadsheet-3.xlsx" in withContext { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            missingApiKeyMessage,
          )

          goldenFileName                  = "spreadsheet-3.json"
          customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

          customerBookFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
              spreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-3.xlsx"))),
              AIInstructions.extractCustomersFromFileInstructions,
              None,
            )
          )

          customerBookFileAIExtract = customerBookFileAIExtracts.head

          _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with shortened notes for spreadsheet-4.csv read in several groups" in withContext {
        context =>
          import context.*

          for {
            _ = assume(
              apiKey.nonEmpty,
              missingApiKeyMessage,
            )

            goldenFileName                  = "spreadsheet-4.json"
            customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

            customerBookFileAIExtracts <- ZIO.scoped(
              aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
                spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-4.csv"))),
                AIInstructions.extractCustomersFromFileInstructions,
                None,
              )
            )

            customerBookFileAIExtractMerged = mergeCustomerBookFileAIExtracts(customerBookFileAIExtracts)

            noteCompactionOutput <- aiClientDataExtraction.noteCompaction(
              customerBookFileAIExtractMerged.extractionNotes,
              AIInstructions.compactCustomerBookFileNotesInstructions,
            )

            customerBookFileAIExtract = customerBookFileAIExtractMerged.copy(extractionNotes =
              noteCompactionOutput.notes
            )

            _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
          } yield ()
      }

      "match the golden answer with no customers for the unrelated spreadsheet spreadsheet-5.csv" in withContext {
        context =>
          import context.*

          for {
            _ = assume(
              apiKey.nonEmpty,
              missingApiKeyMessage,
            )

            goldenFileName                  = "spreadsheet-5.json"
            customerBookFileAIExtractGolden = loadCustomerBookFileAIExtractGolden(goldenFileName)

            customerBookFileAIExtracts <- ZIO.scoped(
              aiClientDataExtraction.extractFromCsv[CustomerBookFileAIExtract](
                spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-5.csv"))),
                AIInstructions.extractCustomersFromFileInstructions,
                None,
              )
            )

            customerBookFileAIExtract = customerBookFileAIExtracts.head

            _ = compareWithGolden(goldenFileName, customerBookFileAIExtract, customerBookFileAIExtractGolden)
          } yield ()
      }
    }
  }
}
