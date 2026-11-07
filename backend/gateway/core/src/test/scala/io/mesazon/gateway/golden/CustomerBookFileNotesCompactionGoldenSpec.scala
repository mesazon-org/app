package io.mesazon.gateway.golden

import io.mesazon.domain.gateway.AppName
import io.mesazon.gateway.clients.*
import io.mesazon.gateway.config.AIClientDataExtractionConfig
import io.mesazon.gateway.service.AIInstructions
import io.mesazon.testkit.base.{SttpTestBackend, ZWordSpecBase}
import zio.*

class CustomerBookFileNotesCompactionGoldenSpec extends ZWordSpecBase {

  private val apiKey =
    sys.env.getOrElse(
      "AI_CLIENT_DATA_EXTRACTION_API_KEY",
      "",
    )

  private val notePrefixRegex = """^\s*(-|\*|\d+\.|Batch)""".r

  private val notesCompactionGoldenCases: List[NotesCompactionGoldenCase] = List(
    (
      rule = "combine exact repeats into one note",
      notes = List.fill(4)("Column Balance was ignored"),
    ),
    (
      rule = "keep related notes that differ in detail as supplied",
      notes = List("Column Balance was ignored", "Column Birthday was ignored"),
    ),
    (
      rule = "keep distinct unrelated notes in first-appearance order",
      notes = List(
        "Column Balance was ignored",
        "The first row looked like data and was skipped",
        "One country code was used for the whole file",
      ),
    ),
    (
      rule = "keep the detail of a note with a quote and a line break, unescaped",
      notes = List(
        "Left out row 7, the heading \"WHOLESALE CLIENTS\",\nbecause nothing is beside it",
        "Left out row 7, the heading \"WHOLESALE CLIENTS\",\nbecause nothing is beside it",
      ),
    ),
    (
      rule = "return notes without a bullet, number or batch prefix",
      notes = List(
        "Column Balance was ignored",
        "Column Balance was ignored",
        "Some cells are merged",
        "Some cells are merged",
      ),
    ),
  )

  type NotesCompactionGoldenCase = (rule: String, notes: List[String])

  private def buildAIClientDataExtraction: AIClientDataExtraction = ZIO
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
          )
        )
      ),
      SttpTestBackend.live,
    )
    .zioValue

  "AIClientDataExtraction" when {
    "noteCompaction" should {
      notesCompactionGoldenCases.foreach { notesCompactionGoldenCase =>
        notesCompactionGoldenCase.rule in {
          assume(
            apiKey.nonEmpty,
            "Set AI_CLIENT_DATA_EXTRACTION_API_KEY to run this manually",
          )

          val noteCompactionOutput = buildAIClientDataExtraction
            .noteCompaction(
              notesCompactionGoldenCase.notes,
              AIInstructions.compactCustomerBookFileNotesInstructions,
            )
            .zioValue

          info(
            s"${notesCompactionGoldenCase.rule}\ninput:\n${notesCompactionGoldenCase.notes.mkString("\n")}\noutput:\n${noteCompactionOutput.notes.mkString("\n")}"
          )

          noteCompactionOutput.notes should not be empty
          noteCompactionOutput.notes.size should be <= notesCompactionGoldenCase.notes.size
          noteCompactionOutput.notes.filter(notePrefixRegex.findFirstIn(_).nonEmpty) shouldBe empty
        }
      }
    }
  }
}
