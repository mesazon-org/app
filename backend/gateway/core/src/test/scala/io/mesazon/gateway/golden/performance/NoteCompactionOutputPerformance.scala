package io.mesazon.gateway.golden.performance

import io.mesazon.domain.gateway.NoteCompactionOutput
import io.mesazon.gateway.golden.performance.GoldenPerformance.{PairingRule, PairingScore}
import io.mesazon.gateway.golden.performance.NoteCompactionOutputDetailKind.*

enum NoteCompactionOutputDetailKind(val label: String, val problemDetail: String) extends GoldenPerformanceDetailKind {
  case Note      extends NoteCompactionOutputDetailKind("Note", "note")
  case NoteCount extends NoteCompactionOutputDetailKind("Note count", "note count")
}

object NoteCompactionOutputPerformance
    extends GoldenPerformanceExtraction[NoteCompactionOutput, NoteCompactionOutputDetailKind] {

  private inline val PositionOffset   = 1
  private inline val PairingScoreFlat = 0
  private inline val HeadingEmpty     = ""
  private inline val HeadingSeparator = " "

  private val similarityThreshold = BigDecimal("0.7")

  private val pairingScoreFlat: PairingScore =
    (primary = BigDecimal(PairingScoreFlat), secondary = BigDecimal(PairingScoreFlat))

  private val notePairingRules: List[PairingRule[String, String]] = List(
    (noteExpected, noteActual) => Option.when(noteExpected == noteActual)(pairingScoreFlat),
    (noteExpected, noteActual) =>
      Some(GoldenPerformance.textCloseness(noteExpected, noteActual).similarity)
        .filter(_ >= similarityThreshold)
        .map(similarity => (primary = similarity, secondary = BigDecimal(PairingScoreFlat))),
  )

  val detailKinds: List[NoteCompactionOutputDetailKind] = NoteCompactionOutputDetailKind.values.toList

  private def noteOneLine(note: String): String = note.linesIterator.nextOption().getOrElse(HeadingEmpty)

  def itemComparisons(
      responseExpected: NoteCompactionOutput,
      responseActual: NoteCompactionOutput,
  ): List[ItemComparison[NoteCompactionOutputDetailKind]] = {
    val notesExpected = responseExpected.notes.toVector
    val notesActual   = responseActual.notes.toVector
    val notePairing   = GoldenPerformance.pairBestMatch(notesExpected.toList, notesActual.toList, notePairingRules)

    notesExpected.indices.toList.map { expectedIndex =>
      val noteActualOpt = notePairing.indexPairs
        .find(_.expectedIndex == expectedIndex)
        .map(indexPair => notesActual(indexPair.actualIndex))

      ItemComparison(
        heading = List(s"#${expectedIndex + PositionOffset}", noteOneLine(notesExpected(expectedIndex)))
          .mkString(HeadingSeparator),
        pairingOutcome = if (noteActualOpt.isDefined) PairingOutcome.Paired else PairingOutcome.Missing,
        detailComparisons = GoldenPerformance
          .compareText(Note, Note.problemDetail, Some(notesExpected(expectedIndex)), noteActualOpt)
          .toList,
        subItemComparisons = Nil,
      )
    } ++ notePairing.actualIndicesUnpaired.map(actualIndex =>
      ItemComparison(
        heading = GoldenPerformance.renderText(noteOneLine(notesActual(actualIndex))),
        pairingOutcome = PairingOutcome.Extra,
        detailComparisons =
          GoldenPerformance.compareText(Note, Note.problemDetail, None, Some(notesActual(actualIndex))).toList,
        subItemComparisons = Nil,
      )
    )
  }

  def answerDetailComparisons(
      responseExpected: NoteCompactionOutput,
      responseActual: NoteCompactionOutput,
  ): List[DetailComparison[NoteCompactionOutputDetailKind]] =
    GoldenPerformance
      .compareNumber(
        NoteCount,
        NoteCount.problemDetail,
        Some(BigDecimal(responseExpected.notes.length)),
        Some(BigDecimal(responseActual.notes.length)),
      )
      .toList

  def generalNotes(response: NoteCompactionOutput): List[String] = Nil

  def itemNotes(response: NoteCompactionOutput): List[List[String]] = Nil
}
