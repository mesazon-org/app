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
import io.mesazon.gateway.json.tapir.{
  catalogueItemVariantUnitCodec,
  catalogueItemVariantUnitPerUnitCodec,
  catalogueItemVariantUnitUnitSizeCodec,
  currencyCodec,
}
import io.mesazon.gateway.service.AIInstructions
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{SttpTestBackend, ZWordSpecBase}
import zio.*

import java.nio.file.*

class CatalogueFileAiExtractGoldenSpec extends ZWordSpecBase {

  private inline val CsvBatchMaxDataRowsCompaction = 15

  private inline val AiClientDataExtractionEnvPrefix = "AI_CLIENT_DATA_EXTRACTION_"

  private inline val AiClientDataExtractionApiKeyEnvName = "AI_CLIENT_DATA_EXTRACTION_API_KEY"

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

  private val assetDirectory =
    Paths.get("src/test/resources/assets-test/CatalogueFileAiExtractGoldenSpec")

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec")

  private val diffsonDirectory =
    Paths.get("src/test/resources/assets-diffson/CatalogueFileAiExtractGoldenSpec")

  private val isProductDefaultSettings: Boolean =
    !sys.env.keys.exists(environmentVariableName =>
      environmentVariableName.startsWith(AiClientDataExtractionEnvPrefix) &&
        environmentVariableName != AiClientDataExtractionApiKeyEnvName
    )

  private val catalogueFileAIExtractCodecFieldComplete: JsonValueCodec[CatalogueFileAIExtract] =
    JsonCodecMaker.make[CatalogueFileAIExtract](
      CodecMakerConfig.withTransientNone(false).withTransientEmpty(false).withTransientDefault(false)
    )

  private def loadCatalogueFileAIExtractGolden(goldenFileName: String): CatalogueFileAIExtract = {
    val golden = goldenDirectory.resolve(goldenFileName)

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[CatalogueFileAIExtract](Files.readString(golden))
  }

  private def withContext[A](csvBatchMaxDataRowsOpt: Option[Int] = None)(f: Context => Task[A]): A =
    ZIO
      .serviceWithZIO[Context](f)
      .provide(
        ZLayer.fromFunction(Context.apply),
        AIClientDataExtraction.live,
        SpreadsheetTool.live,
        ZLayer.succeed(AppName("gateway-api")),
        AIClientDataExtractionConfig.live.map(environment =>
          environment.update[AIClientDataExtractionConfig](aiClientDataExtractionConfig =>
            aiClientDataExtractionConfig.copy(
              scheme = "https",
              host = "api.openai.com",
              port = 443,
              apiKey = apiKey,
              requestTimeout = Duration.fromSeconds(600),
              csvBatchMaxDataRows = csvBatchMaxDataRowsOpt.getOrElse(aiClientDataExtractionConfig.csvBatchMaxDataRows),
            )
          )
        ),
        SttpTestBackend.live,
      )
      .zioValue

  private def mergeCatalogueFileAIExtracts(
      catalogueFileAIExtracts: NonEmptyChunk[CatalogueFileAIExtract]
  ): CatalogueFileAIExtract = {
    val catalogueFileAIExtractsList = catalogueFileAIExtracts.toList

    CatalogueFileAIExtract(
      entriesIdentified = catalogueFileAIExtracts.map(_.entriesIdentified).sum,
      entriesProcessed = catalogueFileAIExtracts.map(_.entriesProcessed).sum,
      candidates = catalogueFileAIExtractsList.flatMap(_.candidates),
      emptyEntryRows = catalogueFileAIExtractsList.flatMap(_.emptyEntryRows),
      unidentifiedEntryRows = catalogueFileAIExtractsList.flatMap(_.unidentifiedEntryRows),
      extractionNotes = catalogueFileAIExtractsList.flatMap(_.extractionNotes),
    )
  }

  private def catalogueFileAIExtractJson(catalogueFileAIExtract: CatalogueFileAIExtract): Json =
    parse(writeToString(catalogueFileAIExtract)(using catalogueFileAIExtractCodecFieldComplete))
      .fold(parsingFailure => fail(s"Failed to parse the encoded answer: [${parsingFailure.message}]"), identity)

  private def compareWithGolden(
      goldenFileName: String,
      catalogueFileAIExtract: CatalogueFileAIExtract,
      catalogueFileAIExtractGolden: CatalogueFileAIExtract,
  ): Unit = {
    val jsonPatch = diff(
      catalogueFileAIExtractJson(catalogueFileAIExtractGolden),
      catalogueFileAIExtractJson(catalogueFileAIExtract),
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
      "match the golden answer for the price-list picture image-1.jpeg sent with its processing message" in withContext() {
        context =>
          import context.*

          for {
            _ = assume(
              apiKey.nonEmpty,
              "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
            )

            catalogueFileProcessingMessage =
              "ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is"

            goldenFileName               = "image-1.json"
            catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

            catalogueFileAIExtract <- aiClientDataExtraction.extractFromImage[CatalogueFileAIExtract](
              FileScannedPath(assetDirectory.resolve("image-1.jpeg")),
              SupportedMediaType.JPEG,
              AIInstructions.extractCatalogueItemsFromImageInstructions,
              Some(catalogueFileProcessingMessage),
            )

            _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
          } yield ()
      }

      "match the golden answer for the price-list picture image-2.jpeg" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "image-2.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtract <- aiClientDataExtraction.extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-2.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no items for the unrelated picture image-3.jpeg" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "image-3.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtract <- aiClientDataExtraction.extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-3.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }
    }

    "extractFromPdf" should {
      "match the golden answer for the PDF price list pdf-1.pdf" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "pdf-1.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtract <- aiClientDataExtraction.extractFromPdf[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-1.pdf")),
            "Catalogue-Price-List.PDF",
            AIInstructions.extractCatalogueItemsFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no items for the unrelated PDF pdf-2.pdf" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "pdf-2.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtract <- aiClientDataExtraction.extractFromPdf[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-2.pdf")),
            "pdf-2.pdf",
            AIInstructions.extractCatalogueItemsFromPdfInstructions,
            None,
          )

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }
    }

    "extractFromCsv" should {
      "match the golden answer for the spreadsheet spreadsheet-1.csv" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "spreadsheet-1.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CatalogueFileAIExtract](
              spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-1.csv"))),
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
            )
          )

          catalogueFileAIExtract = catalogueFileAIExtracts.head

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the spreadsheet spreadsheet-2.xls" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "spreadsheet-2.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CatalogueFileAIExtract](
              spreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-2.xls"))),
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
            )
          )

          catalogueFileAIExtract = catalogueFileAIExtracts.head

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer for the spreadsheet spreadsheet-3.xlsx" in withContext() { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "spreadsheet-3.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CatalogueFileAIExtract](
              spreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-3.xlsx"))),
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
            )
          )

          catalogueFileAIExtract = catalogueFileAIExtracts.head

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with shortened notes for spreadsheet-1.csv read in several groups" in withContext(
        csvBatchMaxDataRowsOpt = Some(CsvBatchMaxDataRowsCompaction)
      ) { context =>
        import context.*

        for {
          _ = assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          goldenFileName               = "spreadsheet-1-notes-compaction.json"
          catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

          catalogueFileAIExtracts <- ZIO.scoped(
            aiClientDataExtraction.extractFromCsv[CatalogueFileAIExtract](
              spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-1.csv"))),
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
            )
          )

          catalogueFileAIExtractMerged = mergeCatalogueFileAIExtracts(catalogueFileAIExtracts)

          noteCompactionOutput <- aiClientDataExtraction.noteCompaction(
            catalogueFileAIExtractMerged.extractionNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )

          catalogueFileAIExtract = catalogueFileAIExtractMerged.copy(extractionNotes = noteCompactionOutput.notes)

          _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
        } yield ()
      }

      "match the golden answer with no items for the unrelated spreadsheet spreadsheet-4.csv" in withContext() {
        context =>
          import context.*

          for {
            _ = assume(
              apiKey.nonEmpty,
              "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
            )

            goldenFileName               = "spreadsheet-4.json"
            catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden(goldenFileName)

            catalogueFileAIExtracts <- ZIO.scoped(
              aiClientDataExtraction.extractFromCsv[CatalogueFileAIExtract](
                spreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-4.csv"))),
                AIInstructions.extractCatalogueItemsFromFileInstructions,
                None,
              )
            )

            catalogueFileAIExtract = catalogueFileAIExtracts.head

            _ = compareWithGolden(goldenFileName, catalogueFileAIExtract, catalogueFileAIExtractGolden)
          } yield ()
      }
    }
  }
}
