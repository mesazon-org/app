package io.mesazon.gateway.golden

import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import io.mesazon.domain.gateway.{AppName, NoteCompactionOutput}
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.golden.performance.{GoldenPerformance, NoteCompactionOutputPerformance}
import io.mesazon.gateway.json.ai.noteCompactionOutputCodec
import io.mesazon.gateway.service.AIInstructions
import io.mesazon.testkit.base.{SttpTestBackend, ZWordSpecBase}
import zio.*

import java.nio.file.*

class CatalogueFileNotesCompactionGoldenSpec extends ZWordSpecBase {

  private type AIClientDataExtractionWithConfig = (
      aiClientDataExtraction: AIClientDataExtraction,
      aiClientDataExtractionConfig: AIClientDataExtractionConfig,
  )

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val notePrefixRegex = """^\s*(-|\*|\d+\.|Batch)""".r

  private val goldenDirectory =
    Paths.get("src/test/resources/assets-golden/CatalogueFileNotesCompactionGoldenSpec")

  private def loadNoteCompactionOutputGolden(noteCompactionCaseID: String): NoteCompactionOutput = {
    val golden = goldenDirectory.resolve(s"$noteCompactionCaseID.json")

    if (!Files.exists(golden)) fail(s"Golden file is missing: ${golden.toAbsolutePath}")
    else readFromString[NoteCompactionOutput](Files.readString(golden))
  }

  private def buildAIClientDataExtractionWithConfig: AIClientDataExtractionWithConfig = (for {
    aiClientDataExtraction       <- ZIO.service[AIClientDataExtraction]
    aiClientDataExtractionConfig <- ZIO.service[AIClientDataExtractionConfig]
  } yield (
    aiClientDataExtraction = aiClientDataExtraction,
    aiClientDataExtractionConfig = aiClientDataExtractionConfig,
  ): AIClientDataExtractionWithConfig)
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
          )
        )
      ),
      SttpTestBackend.live,
    )
    .zioValue

  "AIClientDataExtraction" when {
    "noteCompaction" should {
      "combine exact repeats into one note" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val noteCompactionCaseID     = "notes-compaction-1"
        val noteCompactionInputNotes = List.fill(4)("The Price column was not used")

        val noteCompactionOutputGolden = loadNoteCompactionOutputGolden(noteCompactionCaseID)

        val aiClientDataExtractionWithConfig = buildAIClientDataExtractionWithConfig

        val noteCompactionOutput = aiClientDataExtractionWithConfig.aiClientDataExtraction
          .noteCompaction(
            noteCompactionInputNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )
          .zioValue

        info(
          s"combine exact repeats into one note\ninput:\n${noteCompactionInputNotes.mkString("\n")}\noutput:\n${noteCompactionOutput.notes.mkString("\n")}"
        )
        info(
          GoldenPerformance.renderReport(
            noteCompactionCaseID,
            aiClientDataExtractionWithConfig.aiClientDataExtractionConfig.noteCompactionModel,
            GoldenPerformance.measure(NoteCompactionOutputPerformance)(noteCompactionOutputGolden, noteCompactionOutput),
          )
        )

        noteCompactionOutput.notes should not be empty
        noteCompactionOutput.notes.size should be <= noteCompactionInputNotes.size
        noteCompactionOutput.notes.filter(notePrefixRegex.findFirstIn(_).nonEmpty) shouldBe empty
      }

      "keep related notes that differ in detail as supplied" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val noteCompactionCaseID     = "notes-compaction-2"
        val noteCompactionInputNotes = List("The Price column was not used", "The Stock column was not used")

        val noteCompactionOutputGolden = loadNoteCompactionOutputGolden(noteCompactionCaseID)

        val aiClientDataExtractionWithConfig = buildAIClientDataExtractionWithConfig

        val noteCompactionOutput = aiClientDataExtractionWithConfig.aiClientDataExtraction
          .noteCompaction(
            noteCompactionInputNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )
          .zioValue

        info(
          s"keep related notes that differ in detail as supplied\ninput:\n${noteCompactionInputNotes.mkString("\n")}\noutput:\n${noteCompactionOutput.notes.mkString("\n")}"
        )
        info(
          GoldenPerformance.renderReport(
            noteCompactionCaseID,
            aiClientDataExtractionWithConfig.aiClientDataExtractionConfig.noteCompactionModel,
            GoldenPerformance.measure(NoteCompactionOutputPerformance)(noteCompactionOutputGolden, noteCompactionOutput),
          )
        )

        noteCompactionOutput.notes should not be empty
        noteCompactionOutput.notes.size should be <= noteCompactionInputNotes.size
        noteCompactionOutput.notes.filter(notePrefixRegex.findFirstIn(_).nonEmpty) shouldBe empty
      }

      "keep distinct unrelated notes in first-appearance order" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val noteCompactionCaseID     = "notes-compaction-3"
        val noteCompactionInputNotes = List(
          "The Price column was not used",
          "The first row looked like data and was skipped",
          "Prices are in euros, as the header says",
        )

        val noteCompactionOutputGolden = loadNoteCompactionOutputGolden(noteCompactionCaseID)

        val aiClientDataExtractionWithConfig = buildAIClientDataExtractionWithConfig

        val noteCompactionOutput = aiClientDataExtractionWithConfig.aiClientDataExtraction
          .noteCompaction(
            noteCompactionInputNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )
          .zioValue

        info(
          s"keep distinct unrelated notes in first-appearance order\ninput:\n${noteCompactionInputNotes.mkString("\n")}\noutput:\n${noteCompactionOutput.notes.mkString("\n")}"
        )
        info(
          GoldenPerformance.renderReport(
            noteCompactionCaseID,
            aiClientDataExtractionWithConfig.aiClientDataExtractionConfig.noteCompactionModel,
            GoldenPerformance.measure(NoteCompactionOutputPerformance)(noteCompactionOutputGolden, noteCompactionOutput),
          )
        )

        noteCompactionOutput.notes should not be empty
        noteCompactionOutput.notes.size should be <= noteCompactionInputNotes.size
        noteCompactionOutput.notes.filter(notePrefixRegex.findFirstIn(_).nonEmpty) shouldBe empty
      }

      "keep the detail of a note with a quote and a line break, unescaped" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val noteCompactionCaseID     = "notes-compaction-4"
        val noteCompactionInputNotes = List(
          "Left out row 7, the heading \"FRUITS\",\nbecause nothing is beside it",
          "Left out row 7, the heading \"FRUITS\",\nbecause nothing is beside it",
        )

        val noteCompactionOutputGolden = loadNoteCompactionOutputGolden(noteCompactionCaseID)

        val aiClientDataExtractionWithConfig = buildAIClientDataExtractionWithConfig

        val noteCompactionOutput = aiClientDataExtractionWithConfig.aiClientDataExtraction
          .noteCompaction(
            noteCompactionInputNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )
          .zioValue

        info(
          s"keep the detail of a note with a quote and a line break, unescaped\ninput:\n${noteCompactionInputNotes.mkString("\n")}\noutput:\n${noteCompactionOutput.notes.mkString("\n")}"
        )
        info(
          GoldenPerformance.renderReport(
            noteCompactionCaseID,
            aiClientDataExtractionWithConfig.aiClientDataExtractionConfig.noteCompactionModel,
            GoldenPerformance.measure(NoteCompactionOutputPerformance)(noteCompactionOutputGolden, noteCompactionOutput),
          )
        )

        noteCompactionOutput.notes should not be empty
        noteCompactionOutput.notes.size should be <= noteCompactionInputNotes.size
        noteCompactionOutput.notes.filter(notePrefixRegex.findFirstIn(_).nonEmpty) shouldBe empty
      }

      "return notes without a bullet, number or batch prefix" in {
        assume(
          apiKey.nonEmpty,
          "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
        )

        val noteCompactionCaseID     = "notes-compaction-5"
        val noteCompactionInputNotes = List(
          "The Price column was not used",
          "The Price column was not used",
          "Some cells are merged",
          "Some cells are merged",
        )

        val noteCompactionOutputGolden = loadNoteCompactionOutputGolden(noteCompactionCaseID)

        val aiClientDataExtractionWithConfig = buildAIClientDataExtractionWithConfig

        val noteCompactionOutput = aiClientDataExtractionWithConfig.aiClientDataExtraction
          .noteCompaction(
            noteCompactionInputNotes,
            AIInstructions.compactCatalogueFileNotesInstructions,
          )
          .zioValue

        info(
          s"return notes without a bullet, number or batch prefix\ninput:\n${noteCompactionInputNotes.mkString("\n")}\noutput:\n${noteCompactionOutput.notes.mkString("\n")}"
        )
        info(
          GoldenPerformance.renderReport(
            noteCompactionCaseID,
            aiClientDataExtractionWithConfig.aiClientDataExtractionConfig.noteCompactionModel,
            GoldenPerformance.measure(NoteCompactionOutputPerformance)(noteCompactionOutputGolden, noteCompactionOutput),
          )
        )

        noteCompactionOutput.notes should not be empty
        noteCompactionOutput.notes.size should be <= noteCompactionInputNotes.size
        noteCompactionOutput.notes.filter(notePrefixRegex.findFirstIn(_).nonEmpty) shouldBe empty
      }
    }
  }
}
