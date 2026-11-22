package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString, JsonValueCodec, WriterConfig}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import difflicious.Differ
import difflicious.implicits.*
import difflicious.scalatest.ScalatestDiff
import difflicious.utils.TypeName
import io.github.iltotore.iron.RefinedType
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.json.ai.given
import io.mesazon.gateway.json.tapir.{catalogueItemVariantSaleUnitCodec, currencyCodec}
import io.mesazon.gateway.service.AIInstructions
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.{SttpTestBackend, ZWordSpecBase}
import zio.*

import java.nio.file.*

class CatalogueFileAiExtractGoldenSpec extends ZWordSpecBase, ScalatestDiff {

  private inline val CsvBatchMaxDataRowsDefault    = 100
  private inline val CsvBatchMaxDataRowsCompaction = 15

  private given differRefinedType[WrappedType](using
      mirror: RefinedType.Mirror[WrappedType],
      typeName: TypeName[WrappedType],
  ): Differ[WrappedType] =
    Differ.useEquals[WrappedType](value =>
      value.asInstanceOf[mirror.BaseType] match {
        case text: String => s"\"$text\""
        case other        => other.toString
      }
    )

  private given currencyDiffer: Differ[Currency] =
    Differ.useEquals[Currency](_.toString)

  private given catalogueItemVariantUnitDiffer: Differ[CatalogueItemVariantUnit] =
    Differ.useEquals[CatalogueItemVariantUnit](_.toString)

  private given catalogueItemVariantUnitPerUnitDiffer: Differ[CatalogueItemVariantUnit.PerUnit] =
    Differ.useEquals[CatalogueItemVariantUnit.PerUnit](_.toString)

  private given catalogueItemVariantUnitUnitSizeDiffer: Differ[CatalogueItemVariantUnit.UnitSize] =
    Differ.useEquals[CatalogueItemVariantUnit.UnitSize](_.toString)

  private val catalogueFileAIExtractCodecFieldComplete: JsonValueCodec[CatalogueFileAIExtract] =
    JsonCodecMaker.make[CatalogueFileAIExtract](
      CodecMakerConfig.withTransientNone(false).withTransientEmpty(false).withTransientDefault(false)
    )

  private val captureDirectory = Paths.get("target/golden-capture")

  private def captureCatalogueFileAIExtract(
      goldenFileName: String,
      catalogueFileAIExtract: CatalogueFileAIExtract,
  ): Unit = {
    Files.createDirectories(captureDirectory)
    Files.writeString(
      captureDirectory.resolve(goldenFileName),
      writeToString(catalogueFileAIExtract, WriterConfig.withIndentionStep(2))(using
        catalogueFileAIExtractCodecFieldComplete
      ),
    )
    ()
  }

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val assetDirectory =
    Paths.get("src/test/resources/assets-test/CatalogueFileAiExtractGoldenSpec")

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CatalogueFileAiExtractGoldenSpec")

  private val catalogueFileAIExtractDiffer: Differ[CatalogueFileAIExtract] =
    Differ
      .derivedDeep[CatalogueFileAIExtract]
      .configure(_.candidates)(
        _.pairBy(catalogueItemAIExtract =>
          if (catalogueItemAIExtract.variants.exists(_.sku.isDefined))
            Left(catalogueItemAIExtract.variants.flatMap(_.sku).map(_.value).toSet)
          else Right(catalogueItemAIExtract.name.map(_.value))
        )
      )
      .configure(_.candidates.each.variants)(
        _.pairBy(catalogueItemVariantAIExtract =>
          catalogueItemVariantAIExtract.sku
            .map(catalogueItemVariantSku => Left(catalogueItemVariantSku.value))
            .getOrElse(Right(catalogueItemVariantAIExtract.label.map(_.value)))
        )
      )
      .configure(_.candidates.each.variants.each.attributes)(_.pairBy(_.name))
      .configure(_.candidates.each.variants.each.extractionNotes)(_.pairBy(identity))
      .configure(_.candidates.each.extractionNotes)(_.pairBy(identity))
      .configure(_.extractionNotes)(_.pairBy(identity))
      .configure(_.emptyEntryRows)(_.pairBy(identity))
      .configure(_.unidentifiedEntryRows)(_.pairBy(identity))

  @scala.annotation.nowarn
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

  @scala.annotation.nowarn
  private def compareWithGolden(
      catalogueFileAIExtract: CatalogueFileAIExtract,
      catalogueFileAIExtractGolden: CatalogueFileAIExtract,
  ): Unit =
    catalogueFileAIExtractDiffer.assertNoDiff(catalogueFileAIExtract, catalogueFileAIExtractGolden)

  "AIClientDataExtraction" when {
    "extractFromImage" should {
      "match the golden answer for the price-list picture image-1.jpeg sent with its processing message" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileProcessingMessage =
          "ignore cost, the items that are represented with 1 kg make them 12 kg per box, keep fruits keep them as it is, rest keep them as it is"

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-1.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            Some(catalogueFileProcessingMessage),
          )
          .zioValue

        captureCatalogueFileAIExtract("image-1.json", catalogueFileAIExtract)
      }

      "match the golden answer for the price-list picture image-2.jpeg" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-2.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
          )
          .zioValue

        captureCatalogueFileAIExtract("image-2.json", catalogueFileAIExtract)
      }

      "match the golden answer with no items for the unrelated picture image-3.jpeg" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromImage[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("image-3.jpeg")),
            SupportedMediaType.JPEG,
            AIInstructions.extractCatalogueItemsFromImageInstructions,
            None,
          )
          .zioValue

        captureCatalogueFileAIExtract("image-3.json", catalogueFileAIExtract)
      }
    }

    "extractFromPdf" should {
      "match the golden answer for the PDF price list pdf-1.pdf" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromPdf[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-1.pdf")),
            "Catalogue-Price-List.PDF",
            AIInstructions.extractCatalogueItemsFromPdfInstructions,
            None,
          )
          .zioValue

        captureCatalogueFileAIExtract("pdf-1.json", catalogueFileAIExtract)
      }

      "match the golden answer with no items for the unrelated PDF pdf-2.pdf" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val catalogueFileAIExtract = buildAIClientDataExtraction(CsvBatchMaxDataRowsDefault)
          .extractFromPdf[CatalogueFileAIExtract](
            FileScannedPath(assetDirectory.resolve("pdf-2.pdf")),
            "pdf-2.pdf",
            AIInstructions.extractCatalogueItemsFromPdfInstructions,
            None,
          )
          .zioValue

        captureCatalogueFileAIExtract("pdf-2.json", catalogueFileAIExtract)
      }
    }

    "extractFromCsv" should {
      "match the golden answer for the spreadsheet spreadsheet-1.csv" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

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

        captureCatalogueFileAIExtract("spreadsheet-1.json", catalogueFileAIExtract)
      }

      "match the golden answer for the spreadsheet spreadsheet-2.xls" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

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

        captureCatalogueFileAIExtract("spreadsheet-2.json", catalogueFileAIExtract)
      }

      "match the golden answer for the spreadsheet spreadsheet-3.xlsx" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

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

        captureCatalogueFileAIExtract("spreadsheet-3.json", catalogueFileAIExtract)
      }

      "match the golden answer with shortened notes for spreadsheet-1.csv read in several groups" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

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

        captureCatalogueFileAIExtract("spreadsheet-1-notes-compaction.json", catalogueFileAIExtract)
      }

      "match the golden answer with no items for the unrelated spreadsheet spreadsheet-4.csv" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

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

        captureCatalogueFileAIExtract("spreadsheet-4.json", catalogueFileAIExtract)
      }
    }
  }
}
