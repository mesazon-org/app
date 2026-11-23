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

  private inline val CsvBatchMaxDataRowsDefault    = 100
  private inline val CsvBatchMaxDataRowsCompaction = 15

  private given lcsJson: Lcs[Json] = new Patience[Json]

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val assetDirectory =
    Paths.get("src/test/resources/assets-test/CatalogueFileAiExtractGoldenSpec")

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec")

  private val catalogueFileAIExtractCodecFieldComplete: JsonValueCodec[CatalogueFileAIExtract] =
    JsonCodecMaker.make[CatalogueFileAIExtract](
      CodecMakerConfig.withTransientNone(false).withTransientEmpty(false).withTransientDefault(false)
    )

  private def loadCatalogueFileAIExtractGolden(goldenFileName: String): CatalogueFileAIExtract = {
    val golden = goldenDirectory.resolve(goldenFileName)

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[CatalogueFileAIExtract](Files.readString(golden))
  }

  private def buildAIClientDataExtraction(csvBatchMaxDataRows: Int): AIClientDataExtraction =
    ZIO
      .service[AIClientDataExtraction]
      .provide(
        AIClientDataExtraction.live,
        ZLayer.succeed(AppName("gateway-api")),
        AIClientDataExtractionConfig.live.map(environment =>
          environment.update[AIClientDataExtractionConfig](
            _.copy(
              scheme = "https",
              host = "api.openai.com",
              port = 443,
              apiKey = apiKey,
              requestTimeout = Duration.fromSeconds(600),
              sendMaxRetries = 2,
              sendRetryDelay = Duration.fromSeconds(1),
              csvBatchMaxDataRows = csvBatchMaxDataRows,
              csvBatchParallelism = 4,
            )
          )
        ),
        SttpTestBackend.live,
      )
      .zioValue

  private def buildSpreadsheetTool: SpreadsheetTool =
    ZIO
      .service[SpreadsheetTool]
      .provide(SpreadsheetTool.live)
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
      catalogueFileAIExtract: CatalogueFileAIExtract,
      catalogueFileAIExtractGolden: CatalogueFileAIExtract,
  ): Unit = {
    val jsonPatch = diff(
      catalogueFileAIExtractJson(catalogueFileAIExtractGolden),
      catalogueFileAIExtractJson(catalogueFileAIExtract),
    )

    if (jsonPatch.ops.nonEmpty) fail(jsonPatch.asJson.spaces2)
  }

  "AIClientDataExtraction" when {
    "extractFromImage" should {
      "match the golden answer for the price-list picture image-1.jpeg sent with its processing message" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileProcessingMessage =
          "ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is"

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("image-1.json")

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-1.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            Some(catalogueFileProcessingMessage),
          )
          .zioValue

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer for the price-list picture image-2.jpeg" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("image-2.json")

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-2.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
          )
          .zioValue

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer with no items for the unrelated picture image-3.jpeg" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("image-3.json")

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-3.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
          )
          .zioValue

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }
    }

    "extractFromPdf" should {
      "match the golden answer for the PDF price list pdf-1.pdf" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("pdf-1.json")

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromPdf[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-1.pdf")),
            "Catalogue-Price-List.PDF",
            AIInstructions.extractCatalogueItemsFromPdfInstructions,
            None,
          )
          .zioValue

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer with no items for the unrelated PDF pdf-2.pdf" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("pdf-2.json")

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromPdf[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-2.pdf")),
            "pdf-2.pdf",
            AIInstructions.extractCatalogueItemsFromPdfInstructions,
            None,
          )
          .zioValue

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }
    }

    "extractFromCsv" should {
      "match the golden answer for the spreadsheet spreadsheet-1.csv" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("spreadsheet-1.json")

        val catalogueFileAIExtract = ZIO
          .scoped(
            buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
              .extractFromCsv[CatalogueFileAIExtract](
                buildSpreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-1.csv"))),
                AIInstructions.extractCatalogueItemsFromFileInstructions,
                None,
              )
          )
          .zioValue
          .head

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer for the spreadsheet spreadsheet-2.xls" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("spreadsheet-2.json")

        val catalogueFileAIExtract = ZIO
          .scoped(
            buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
              .extractFromCsv[CatalogueFileAIExtract](
                buildSpreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-2.xls"))),
                AIInstructions.extractCatalogueItemsFromFileInstructions,
                None,
              )
          )
          .zioValue
          .head

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer for the spreadsheet spreadsheet-3.xlsx" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("spreadsheet-3.json")

        val catalogueFileAIExtract = ZIO
          .scoped(
            buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
              .extractFromCsv[CatalogueFileAIExtract](
                buildSpreadsheetTool.convertExcelToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-3.xlsx"))),
                AIInstructions.extractCatalogueItemsFromFileInstructions,
                None,
              )
          )
          .zioValue
          .head

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer with shortened notes for spreadsheet-1.csv read in several groups" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("spreadsheet-1-notes-compaction.json")

        val aiClientDataExtraction = buildAIClientDataExtraction(CsvBatchMaxDataRowsCompaction)

        val catalogueFileAIExtracts = ZIO
          .scoped(
            aiClientDataExtraction.extractFromCsv[CatalogueFileAIExtract](
              buildSpreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-1.csv"))),
              AIInstructions.extractCatalogueItemsFromFileInstructions,
              None,
            )
          )
          .zioValue

        val catalogueFileAIExtractMerged = mergeCatalogueFileAIExtracts(catalogueFileAIExtracts)

        val noteCompactionOutput = aiClientDataExtraction
          .noteCompaction(
            catalogueFileAIExtractMerged.extractionNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )
          .zioValue

        val catalogueFileAIExtract = catalogueFileAIExtractMerged.copy(extractionNotes = noteCompactionOutput.notes)

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }

      "match the golden answer with no items for the unrelated spreadsheet spreadsheet-4.csv" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtractGolden = loadCatalogueFileAIExtractGolden("spreadsheet-4.json")

        val catalogueFileAIExtract = ZIO
          .scoped(
            buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
              .extractFromCsv[CatalogueFileAIExtract](
                buildSpreadsheetTool.convertToCsv(FileScannedPath(assetDirectory.resolve("spreadsheet-4.csv"))),
                AIInstructions.extractCatalogueItemsFromFileInstructions,
                None,
              )
          )
          .zioValue
          .head

        compareWithGolden(catalogueFileAIExtract, catalogueFileAIExtractGolden)
      }
    }
  }
}
