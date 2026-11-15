package io.mesazon.gateway.unit.golden.performance

import io.mesazon.domain.gateway.NoteCompactionOutput
import io.mesazon.gateway.golden.performance.*
import io.mesazon.gateway.golden.performance.NoteCompactionOutputDetailKind.*
import io.mesazon.testkit.base.WordSpecBase

class NoteCompactionOutputPerformanceSpec extends WordSpecBase {

  private val noteCompactionOutputGolden = NoteCompactionOutput(
    notes = List(
      "The Price column was not used",
      "Left out row 7, the heading \"FRUITS\",\nbecause nothing is beside it",
      "Some cells are merged",
    )
  )

  private val detailKindTalliesGolden = List(
    DetailKindTally(Note, matched = 3, mismatched = 0, missing = 0, extra = 0, percentageRight = BigDecimal("100.0")),
    DetailKindTally(
      NoteCount,
      matched = 1,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
  )

  private val goldenPerformanceResultGolden = GoldenPerformanceResult(
    rightCount = 4,
    countedCount = 4,
    overallPercentage = BigDecimal("100.0"),
    itemTally = ItemTally(paired = 3, missing = 0, extra = 0),
    detailKindTallies = detailKindTalliesGolden,
    noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
    noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
    itemProblems = Nil,
    answerDetailComparisonsNonMatched = Nil,
  )

  "NoteCompactionOutputPerformance" when {
    "measure" should {
      "score 100% with no problems when the answer equals the golden" in {
        val noteCompactionOutputActual = noteCompactionOutputGolden

        val goldenPerformanceResult =
          GoldenPerformance.measure(NoteCompactionOutputPerformance)(
            noteCompactionOutputGolden,
            noteCompactionOutputActual,
          )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden
      }

      "score 100% with no problems when the answer lists the same notes in a different order" in {
        val noteCompactionOutputActual =
          noteCompactionOutputGolden.copy(notes = noteCompactionOutputGolden.notes.reverse)

        noteCompactionOutputActual shouldNot equal(noteCompactionOutputGolden)

        val goldenPerformanceResult =
          GoldenPerformance.measure(NoteCompactionOutputPerformance)(
            noteCompactionOutputGolden,
            noteCompactionOutputActual,
          )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden
      }

      "report a reworded note as a mismatch with its text closeness while it still pairs" in {
        val noteCompactionOutputActual = noteCompactionOutputGolden.copy(
          notes = "The Price column is not used" :: noteCompactionOutputGolden.notes.tail
        )

        val goldenPerformanceResult =
          GoldenPerformance.measure(NoteCompactionOutputPerformance)(
            noteCompactionOutputGolden,
            noteCompactionOutputActual,
          )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden.copy(
          rightCount = 3,
          overallPercentage = BigDecimal("75.0"),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == Note =>
              detailKindTally.copy(matched = 2, mismatched = 1, percentageRight = BigDecimal("66.7"))
            case detailKindTally => detailKindTally
          },
          itemProblems = List(
            ItemProblems(
              heading = "#1 The Price column was not used",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 1,
              detailComparisonsNonMatched = List(
                DetailComparison(
                  detailKind = Note,
                  detail = "note",
                  expectedOpt = Some("\"The Price column was not used\""),
                  actualOpt = Some("\"The Price column is not used\""),
                  outcome = DetailOutcome.Mismatched,
                  closenessOpt = Some("2 characters off, 93% alike"),
                )
              ),
              subItemProblems = Nil,
            )
          ),
        )
      }

      "report a dropped note as missing and an unknown note as extra" in {
        val noteCompactionOutputActual = NoteCompactionOutput(
          notes = List(
            "The Price column was not used",
            "Some cells are merged",
            "Prices are in euros, as the header says",
          )
        )

        val goldenPerformanceResult =
          GoldenPerformance.measure(NoteCompactionOutputPerformance)(
            noteCompactionOutputGolden,
            noteCompactionOutputActual,
          )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden.copy(
          rightCount = 3,
          countedCount = 5,
          overallPercentage = BigDecimal("60.0"),
          itemTally = ItemTally(paired = 2, missing = 1, extra = 1),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == Note =>
              detailKindTally.copy(matched = 2, missing = 1, extra = 1, percentageRight = BigDecimal("50.0"))
            case detailKindTally => detailKindTally
          },
          itemProblems = List(
            ItemProblems(
              heading = "#2 Left out row 7, the heading \"FRUITS\",",
              pairingOutcome = PairingOutcome.Missing,
              detailCount = 1,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            ),
            ItemProblems(
              heading = "\"Prices are in euros, as the header says\"",
              pairingOutcome = PairingOutcome.Extra,
              detailCount = 1,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            ),
          ),
        )
      }

      "report a kept repeat as an extra note and a note count mismatch" in {
        val noteCompactionOutputActual = noteCompactionOutputGolden.copy(
          notes = noteCompactionOutputGolden.notes :+ "The Price column was not used"
        )

        val goldenPerformanceResult =
          GoldenPerformance.measure(NoteCompactionOutputPerformance)(
            noteCompactionOutputGolden,
            noteCompactionOutputActual,
          )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden.copy(
          rightCount = 3,
          countedCount = 5,
          overallPercentage = BigDecimal("60.0"),
          itemTally = ItemTally(paired = 3, missing = 0, extra = 1),
          detailKindTallies = List(
            DetailKindTally(
              Note,
              matched = 3,
              mismatched = 0,
              missing = 0,
              extra = 1,
              percentageRight = BigDecimal("75.0"),
            ),
            DetailKindTally(
              NoteCount,
              matched = 0,
              mismatched = 1,
              missing = 0,
              extra = 0,
              percentageRight = BigDecimal("0.0"),
            ),
          ),
          itemProblems = List(
            ItemProblems(
              heading = "\"The Price column was not used\"",
              pairingOutcome = PairingOutcome.Extra,
              detailCount = 1,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            )
          ),
          answerDetailComparisonsNonMatched = List(
            DetailComparison(
              detailKind = NoteCount,
              detail = "note count",
              expectedOpt = Some("3"),
              actualOpt = Some("4"),
              outcome = DetailOutcome.Mismatched,
              closenessOpt = None,
            )
          ),
        )
      }
    }
  }
}
