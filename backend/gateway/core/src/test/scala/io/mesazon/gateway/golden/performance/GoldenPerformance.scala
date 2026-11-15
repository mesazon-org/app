package io.mesazon.gateway.golden.performance

import scala.math.BigDecimal.RoundingMode

trait GoldenPerformanceDetailKind {
  def label: String
}

enum DetailOutcome {
  case Matched
  case Mismatched
  case Missing
  case Extra
}

enum PairingOutcome {
  case Paired
  case Missing
  case Extra
}

case class DetailComparison[DetailKind <: GoldenPerformanceDetailKind](
    detailKind: DetailKind,
    detail: String,
    expectedOpt: Option[String],
    actualOpt: Option[String],
    outcome: DetailOutcome,
    closenessOpt: Option[String],
)

case class SubItemComparison[DetailKind <: GoldenPerformanceDetailKind](
    heading: String,
    pairingOutcome: PairingOutcome,
    detailComparisons: List[DetailComparison[DetailKind]],
)

case class ItemComparison[DetailKind <: GoldenPerformanceDetailKind](
    heading: String,
    pairingOutcome: PairingOutcome,
    detailComparisons: List[DetailComparison[DetailKind]],
    subItemComparisons: List[SubItemComparison[DetailKind]],
)

trait GoldenPerformanceExtraction[Response, DetailKind <: GoldenPerformanceDetailKind] {
  def detailKinds: List[DetailKind]

  def itemComparisons(responseExpected: Response, responseActual: Response): List[ItemComparison[DetailKind]]

  def answerDetailComparisons(responseExpected: Response, responseActual: Response): List[DetailComparison[DetailKind]]

  def generalNotes(response: Response): List[String]

  def itemNotes(response: Response): List[List[String]]
}

case class DetailKindTally[DetailKind <: GoldenPerformanceDetailKind](
    detailKind: DetailKind,
    matched: Int,
    mismatched: Int,
    missing: Int,
    extra: Int,
    percentageRight: BigDecimal,
)

case class ItemTally(paired: Int, missing: Int, extra: Int)

case class NoteCounts(general: Int, itemsWithNotes: Int, itemNotes: Int)

case class SubItemProblems[DetailKind <: GoldenPerformanceDetailKind](
    heading: String,
    pairingOutcome: PairingOutcome,
    detailCount: Int,
    detailComparisonsNonMatched: List[DetailComparison[DetailKind]],
)

case class ItemProblems[DetailKind <: GoldenPerformanceDetailKind](
    heading: String,
    pairingOutcome: PairingOutcome,
    detailCount: Int,
    detailComparisonsNonMatched: List[DetailComparison[DetailKind]],
    subItemProblems: List[SubItemProblems[DetailKind]],
)

case class GoldenPerformanceResult[DetailKind <: GoldenPerformanceDetailKind](
    rightCount: Int,
    countedCount: Int,
    overallPercentage: BigDecimal,
    itemTally: ItemTally,
    detailKindTallies: List[DetailKindTally[DetailKind]],
    noteCountsAnswer: NoteCounts,
    noteCountsGolden: NoteCounts,
    itemProblems: List[ItemProblems[DetailKind]],
    answerDetailComparisonsNonMatched: List[DetailComparison[DetailKind]],
)

case class TextCloseness(characterDistance: Int, similarity: BigDecimal)

object GoldenPerformance {

  private type CodePointWithIndex = (codePoint: Int, index: Int)

  private type IndexPairScored = (indexPair: IndexPair, pairingScore: PairingScore)

  private type ProblemLine = (indent: String, detailComparison: DetailComparison[?])

  private inline val EditCost               = 1
  private inline val NoEditCost             = 0
  private inline val DistanceRowStart       = 0
  private inline val DistanceRowIndexOffset = 1
  private inline val LengthEmpty            = 0
  private inline val SimilarityFull         = 1
  private inline val CompareToEqual         = 0
  private inline val CharacterDistanceOne   = 1
  private inline val DetailCountOne         = 1
  private inline val PaddingNone            = 0
  private inline val PercentMultiplier      = 100
  private inline val OverallPercentageScale = 1
  private inline val WholePercentScale      = 0
  private inline val PresentValue           = "present"
  private inline val TextQuote              = "\""
  private inline val Space                  = " "
  private inline val ColumnSpacing          = "  "
  private inline val ItemIndent             = "  "
  private inline val SubItemIndent          = "    "
  private inline val DetailEmpty            = ""
  private inline val CountedNone            = 0
  private inline val ValueTextNone          = ""
  private inline val DetailHeader           = "Detail"
  private inline val RightHeader            = "Right"
  private inline val WrongHeader            = "Wrong"
  private inline val MissingHeader          = "Missing"
  private inline val ExtraHeader            = "Extra"
  private inline val PercentageRightHeader  = "% right"
  private inline val ProblemsHeading        = "Problems"
  private inline val ProblemsNoneLine       = "Problems: none"
  private inline val SummaryLinesHeading    = "Summary lines"

  type PairingScore = (primary: BigDecimal, secondary: BigDecimal)

  type PairingRule[Expected, Actual] = (Expected, Actual) => Option[PairingScore]

  type IndexPair = (expectedIndex: Int, actualIndex: Int)

  case class Pairing(
      indexPairs: List[IndexPair],
      expectedIndicesUnpaired: List[Int],
      actualIndicesUnpaired: List[Int],
  )

  private def levenshteinDistance(codePointsExpected: Vector[Int], codePointsActual: Vector[Int]): Int = {
    val codePointsActualWithIndex: Vector[CodePointWithIndex] = codePointsActual.zipWithIndex
    val distanceRowFirst = Vector.range(DistanceRowStart, codePointsActual.length + DistanceRowIndexOffset)

    codePointsExpected
      .foldLeft(distanceRowFirst) { (distanceRowPrevious, codePointExpected) =>
        codePointsActualWithIndex.foldLeft(Vector(distanceRowPrevious.head + EditCost)) {
          (distanceRowCurrent, codePointActualWithIndex) =>
            val substitutionCost =
              if (codePointExpected == codePointActualWithIndex.codePoint) NoEditCost else EditCost

            distanceRowCurrent :+ List(
              distanceRowPrevious(codePointActualWithIndex.index + DistanceRowIndexOffset) + EditCost,
              distanceRowCurrent(codePointActualWithIndex.index) + EditCost,
              distanceRowPrevious(codePointActualWithIndex.index) + substitutionCost,
            ).min
        }
      }
      .last
  }

  private def pairGreedily[Expected, Actual](
      itemsExpectedWithIndex: List[(item: Expected, index: Int)],
      itemsActualWithIndex: List[(item: Actual, index: Int)],
      pairing: Pairing,
      pairingRule: PairingRule[Expected, Actual],
  ): Pairing = {
    val indexPairsScoredSorted = (for {
      itemExpectedWithIndex <- itemsExpectedWithIndex.filter(itemExpectedWithIndex =>
        pairing.expectedIndicesUnpaired.contains(itemExpectedWithIndex.index)
      )
      itemActualWithIndex <- itemsActualWithIndex.filter(itemActualWithIndex =>
        pairing.actualIndicesUnpaired.contains(itemActualWithIndex.index)
      )
      pairingScore <- pairingRule(itemExpectedWithIndex.item, itemActualWithIndex.item).toList
    } yield (
      indexPair = (expectedIndex = itemExpectedWithIndex.index, actualIndex = itemActualWithIndex.index),
      pairingScore = pairingScore,
    ): IndexPairScored).sortBy(indexPairScored =>
      (
        -indexPairScored.pairingScore.primary,
        -indexPairScored.pairingScore.secondary,
        (indexPairScored.indexPair.expectedIndex - indexPairScored.indexPair.actualIndex).abs,
        indexPairScored.indexPair.expectedIndex,
        indexPairScored.indexPair.actualIndex,
      )
    )

    val indexPairsChosen = indexPairsScoredSorted.foldLeft(List.empty[IndexPair]) {
      (indexPairsChosenSoFar, indexPairScored) =>
        val isExpectedTaken =
          indexPairsChosenSoFar.exists(_.expectedIndex == indexPairScored.indexPair.expectedIndex)
        val isActualTaken = indexPairsChosenSoFar.exists(_.actualIndex == indexPairScored.indexPair.actualIndex)

        if (isExpectedTaken || isActualTaken) indexPairsChosenSoFar
        else indexPairsChosenSoFar :+ indexPairScored.indexPair
    }

    Pairing(
      indexPairs = (pairing.indexPairs ++ indexPairsChosen).sortBy(_.expectedIndex),
      expectedIndicesUnpaired = pairing.expectedIndicesUnpaired.filterNot(expectedIndex =>
        indexPairsChosen.exists(_.expectedIndex == expectedIndex)
      ),
      actualIndicesUnpaired =
        pairing.actualIndicesUnpaired.filterNot(actualIndex => indexPairsChosen.exists(_.actualIndex == actualIndex)),
    )
  }

  private def percentageRight(rightCount: Int, countedCount: Int): BigDecimal =
    (if (countedCount == CountedNone) BigDecimal(PercentMultiplier)
     else BigDecimal(rightCount * PercentMultiplier) / BigDecimal(countedCount))
      .setScale(OverallPercentageScale, RoundingMode.HALF_UP)

  private def sortedByDetailKind[DetailKind <: GoldenPerformanceDetailKind](
      detailKinds: List[DetailKind],
      detailComparisons: List[DetailComparison[DetailKind]],
  ): List[DetailComparison[DetailKind]] =
    detailComparisons.sortBy(detailComparison => detailKinds.indexOf(detailComparison.detailKind))

  private def itemDetailComparisons[DetailKind <: GoldenPerformanceDetailKind](
      itemComparison: ItemComparison[DetailKind]
  ): List[DetailComparison[DetailKind]] =
    itemComparison.detailComparisons ++ itemComparison.subItemComparisons.flatMap(_.detailComparisons)

  private def subItemProblemsOpt[DetailKind <: GoldenPerformanceDetailKind](
      detailKinds: List[DetailKind],
      subItemComparison: SubItemComparison[DetailKind],
  ): Option[SubItemProblems[DetailKind]] = {
    val detailComparisonsNonMatched = subItemComparison.pairingOutcome match {
      case PairingOutcome.Paired =>
        sortedByDetailKind(
          detailKinds,
          subItemComparison.detailComparisons.filterNot(_.outcome == DetailOutcome.Matched),
        )
      case PairingOutcome.Missing | PairingOutcome.Extra => Nil
    }

    Option.when(subItemComparison.pairingOutcome != PairingOutcome.Paired || detailComparisonsNonMatched.nonEmpty)(
      SubItemProblems(
        heading = subItemComparison.heading,
        pairingOutcome = subItemComparison.pairingOutcome,
        detailCount = subItemComparison.detailComparisons.length,
        detailComparisonsNonMatched = detailComparisonsNonMatched,
      )
    )
  }

  private def itemProblemsOpt[DetailKind <: GoldenPerformanceDetailKind](
      detailKinds: List[DetailKind],
      itemComparison: ItemComparison[DetailKind],
  ): Option[ItemProblems[DetailKind]] = {
    val isPaired                    = itemComparison.pairingOutcome == PairingOutcome.Paired
    val detailComparisonsNonMatched =
      if (isPaired)
        sortedByDetailKind(detailKinds, itemComparison.detailComparisons.filterNot(_.outcome == DetailOutcome.Matched))
      else Nil
    val subItemProblems =
      if (isPaired) itemComparison.subItemComparisons.flatMap(subItemProblemsOpt(detailKinds, _)) else Nil

    Option.when(!isPaired || detailComparisonsNonMatched.nonEmpty || subItemProblems.nonEmpty)(
      ItemProblems(
        heading = itemComparison.heading,
        pairingOutcome = itemComparison.pairingOutcome,
        detailCount = itemDetailComparisons(itemComparison).length,
        detailComparisonsNonMatched = detailComparisonsNonMatched,
        subItemProblems = subItemProblems,
      )
    )
  }

  private def noteCounts[Response](
      extraction: GoldenPerformanceExtraction[Response, ?],
      response: Response,
  ): NoteCounts = {
    val itemNotes = extraction.itemNotes(response)

    NoteCounts(
      general = extraction.generalNotes(response).length,
      itemsWithNotes = itemNotes.count(_.nonEmpty),
      itemNotes = itemNotes.map(_.length).sum,
    )
  }

  private def codePointLength(text: String): Int = text.codePointCount(LengthEmpty, text.length)

  private def padRight(text: String, width: Int): String =
    text + Space * (width - codePointLength(text)).max(PaddingNone)

  private def padLeft(text: String, width: Int): String =
    Space * (width - codePointLength(text)).max(PaddingNone) + text

  private def detailCountText(detailCount: Int): String =
    if (detailCount == DetailCountOne) s"$detailCount detail" else s"$detailCount details"

  private def valueText(detailComparison: DetailComparison[?]): String =
    (detailComparison.expectedOpt, detailComparison.actualOpt) match {
      case (Some(valueExpected), Some(valueActual)) => s"expected $valueExpected, got $valueActual"
      case (Some(valueExpected), None)              => s"missing from the answer (expected $valueExpected)"
      case (None, Some(valueActual))                => s"extra in the answer (got $valueActual)"
      case (None, None)                             => ValueTextNone
    }

  private def renderProblemLine(problemLine: ProblemLine, detailWidth: Int): List[String] =
    s"${problemLine.indent}${padRight(problemLine.detailComparison.detail, detailWidth)}${valueText(problemLine.detailComparison)}" ::
      problemLine.detailComparison.closenessOpt
        .map(closeness => s"${Space * (codePointLength(problemLine.indent) + detailWidth)}($closeness)")
        .toList

  private def renderTableLines[DetailKind <: GoldenPerformanceDetailKind](
      detailKindTallies: List[DetailKindTally[DetailKind]]
  ): List[String] = {
    val detailKindTalliesShown = detailKindTallies.filter(detailKindTally =>
      detailKindTally.matched + detailKindTally.mismatched + detailKindTally.missing + detailKindTally.extra > CountedNone
    )
    val detailWidth =
      (DetailHeader :: detailKindTalliesShown.map(_.detailKind.label)).map(codePointLength).max + ColumnSpacing.length

    List(
      padRight(DetailHeader, detailWidth),
      RightHeader,
      Space,
      WrongHeader,
      Space,
      MissingHeader,
      Space,
      ExtraHeader,
      ColumnSpacing,
      PercentageRightHeader,
    ).mkString :: detailKindTalliesShown.map(detailKindTally =>
      List(
        padRight(detailKindTally.detailKind.label, detailWidth),
        padLeft(detailKindTally.matched.toString, RightHeader.length),
        Space,
        padLeft(detailKindTally.mismatched.toString, WrongHeader.length),
        Space,
        padLeft(detailKindTally.missing.toString, MissingHeader.length),
        Space,
        padLeft(detailKindTally.extra.toString, ExtraHeader.length),
        ColumnSpacing,
        padLeft(s"${detailKindTally.percentageRight}%", PercentageRightHeader.length),
      ).mkString
    )
  }

  private def renderProblemsLines[DetailKind <: GoldenPerformanceDetailKind](
      result: GoldenPerformanceResult[DetailKind]
  ): List[String] = {
    val problemLinesAll: List[ProblemLine] =
      result.itemProblems.flatMap(itemProblems =>
        itemProblems.detailComparisonsNonMatched.map(detailComparison =>
          (indent = ItemIndent, detailComparison = detailComparison): ProblemLine
        ) ++ itemProblems.subItemProblems.flatMap(
          _.detailComparisonsNonMatched.map(detailComparison =>
            (indent = SubItemIndent, detailComparison = detailComparison): ProblemLine
          )
        )
      ) ++ result.answerDetailComparisonsNonMatched.map(detailComparison =>
        (indent = ItemIndent, detailComparison = detailComparison): ProblemLine
      )
    val detailWidth = (DetailEmpty :: problemLinesAll.map(_.detailComparison.detail))
      .map(codePointLength)
      .max + ColumnSpacing.length
    val itemProblemsLines = result.itemProblems.flatMap(itemProblems =>
      itemProblems.pairingOutcome match {
        case PairingOutcome.Paired =>
          itemProblems.heading :: (itemProblems.detailComparisonsNonMatched.flatMap(detailComparison =>
            renderProblemLine((indent = ItemIndent, detailComparison = detailComparison), detailWidth)
          ) ++ itemProblems.subItemProblems.flatMap(subItemProblems =>
            subItemProblems.pairingOutcome match {
              case PairingOutcome.Paired =>
                s"$ItemIndent${subItemProblems.heading}" :: subItemProblems.detailComparisonsNonMatched.flatMap(
                  detailComparison =>
                    renderProblemLine((indent = SubItemIndent, detailComparison = detailComparison), detailWidth)
                )
              case PairingOutcome.Missing =>
                List(
                  s"$ItemIndent${subItemProblems.heading} — missing from the answer (${detailCountText(subItemProblems.detailCount)})"
                )
              case PairingOutcome.Extra =>
                List(
                  s"$ItemIndent${subItemProblems.heading} — extra in the answer (${detailCountText(subItemProblems.detailCount)})"
                )
            }
          ))
        case PairingOutcome.Missing =>
          List(s"${itemProblems.heading} — missing from the answer (${detailCountText(itemProblems.detailCount)})")
        case PairingOutcome.Extra =>
          List(s"Extra item ${itemProblems.heading} (${detailCountText(itemProblems.detailCount)})")
      }
    )
    val answerProblemsLines =
      if (result.answerDetailComparisonsNonMatched.isEmpty) Nil
      else
        SummaryLinesHeading :: result.answerDetailComparisonsNonMatched.flatMap(detailComparison =>
          renderProblemLine((indent = ItemIndent, detailComparison = detailComparison), detailWidth)
        )

    if (result.itemProblems.isEmpty && result.answerDetailComparisonsNonMatched.isEmpty) List(ProblemsNoneLine)
    else ProblemsHeading :: itemProblemsLines ++ answerProblemsLines
  }

  def textCloseness(textExpected: String, textActual: String): TextCloseness = {
    val codePointsExpected = textExpected.codePoints().toArray.toVector
    val codePointsActual   = textActual.codePoints().toArray.toVector
    val lengthMax          = codePointsExpected.length.max(codePointsActual.length)
    val characterDistance  = levenshteinDistance(codePointsExpected, codePointsActual)
    val similarity         =
      if (lengthMax == LengthEmpty) BigDecimal(SimilarityFull)
      else BigDecimal(SimilarityFull) - BigDecimal(characterDistance) / BigDecimal(lengthMax)

    TextCloseness(characterDistance = characterDistance, similarity = similarity)
  }

  def renderTextCloseness(textCloseness: TextCloseness): String = {
    val characterWord =
      if (textCloseness.characterDistance == CharacterDistanceOne) "character" else "characters"
    val similarityWholePercent =
      (textCloseness.similarity * PercentMultiplier).setScale(WholePercentScale, RoundingMode.HALF_UP)

    s"${textCloseness.characterDistance} $characterWord off, $similarityWholePercent% alike"
  }

  def renderNumber(number: BigDecimal): String = number.bigDecimal.stripTrailingZeros.toPlainString

  def renderText(text: String): String = s"$TextQuote$text$TextQuote"

  def compareText[DetailKind <: GoldenPerformanceDetailKind](
      detailKind: DetailKind,
      detail: String,
      textExpectedOpt: Option[String],
      textActualOpt: Option[String],
  ): Option[DetailComparison[DetailKind]] =
    compareValue(detailKind, detail, textExpectedOpt, textActualOpt, _ == _, renderText).map(detailComparison =>
      detailComparison.copy(closenessOpt = for {
        textExpected <- textExpectedOpt
        textActual   <- textActualOpt
        if detailComparison.outcome == DetailOutcome.Mismatched
      } yield renderTextCloseness(textCloseness(textExpected, textActual)))
    )

  def compareNumber[DetailKind <: GoldenPerformanceDetailKind](
      detailKind: DetailKind,
      detail: String,
      numberExpectedOpt: Option[BigDecimal],
      numberActualOpt: Option[BigDecimal],
  ): Option[DetailComparison[DetailKind]] =
    compareValue(
      detailKind,
      detail,
      numberExpectedOpt,
      numberActualOpt,
      (numberExpected, numberActual) => numberExpected.bigDecimal.compareTo(numberActual.bigDecimal) == CompareToEqual,
      renderNumber,
    )

  def comparePresence[DetailKind <: GoldenPerformanceDetailKind](
      detailKind: DetailKind,
      detail: String,
      isExpectedPresent: Boolean,
      isActualPresent: Boolean,
  ): Option[DetailComparison[DetailKind]] =
    compareValue(
      detailKind,
      detail,
      Option.when(isExpectedPresent)(PresentValue),
      Option.when(isActualPresent)(PresentValue),
      _ == _,
      identity,
    )

  def compareValue[DetailKind <: GoldenPerformanceDetailKind, Value](
      detailKind: DetailKind,
      detail: String,
      valueExpectedOpt: Option[Value],
      valueActualOpt: Option[Value],
      isEqual: (Value, Value) => Boolean,
      render: Value => String,
  ): Option[DetailComparison[DetailKind]] = {
    val outcomeOpt = (valueExpectedOpt, valueActualOpt) match {
      case (Some(valueExpected), Some(valueActual)) =>
        Some(if (isEqual(valueExpected, valueActual)) DetailOutcome.Matched else DetailOutcome.Mismatched)
      case (Some(_), None) => Some(DetailOutcome.Missing)
      case (None, Some(_)) => Some(DetailOutcome.Extra)
      case (None, None)    => None
    }

    outcomeOpt.map(outcome =>
      DetailComparison(
        detailKind = detailKind,
        detail = detail,
        expectedOpt = valueExpectedOpt.map(render),
        actualOpt = valueActualOpt.map(render),
        outcome = outcome,
        closenessOpt = None,
      )
    )
  }

  def pairBestMatch[Expected, Actual](
      itemsExpected: List[Expected],
      itemsActual: List[Actual],
      pairingRules: List[PairingRule[Expected, Actual]],
  ): Pairing = {
    val itemsExpectedWithIndex: List[(item: Expected, index: Int)] = itemsExpected.zipWithIndex
    val itemsActualWithIndex: List[(item: Actual, index: Int)]     = itemsActual.zipWithIndex
    val pairingEmpty                                               = Pairing(
      indexPairs = Nil,
      expectedIndicesUnpaired = itemsExpected.indices.toList,
      actualIndicesUnpaired = itemsActual.indices.toList,
    )

    pairingRules.foldLeft(pairingEmpty)((pairing, pairingRule) =>
      pairGreedily(itemsExpectedWithIndex, itemsActualWithIndex, pairing, pairingRule)
    )
  }

  def pairInOrder(expectedIndices: List[Int], actualIndices: List[Int]): Pairing = {
    val expectedIndicesVector = expectedIndices.toVector
    val actualIndicesVector   = actualIndices.toVector
    val pairedCount           = expectedIndices.length.min(actualIndices.length)

    Pairing(
      indexPairs = List.tabulate(pairedCount)(position =>
        (expectedIndex = expectedIndicesVector(position), actualIndex = actualIndicesVector(position))
      ),
      expectedIndicesUnpaired = expectedIndices.drop(pairedCount),
      actualIndicesUnpaired = actualIndices.drop(pairedCount),
    )
  }

  def measure[Response, DetailKind <: GoldenPerformanceDetailKind](
      extraction: GoldenPerformanceExtraction[Response, DetailKind]
  )(responseExpected: Response, responseActual: Response): GoldenPerformanceResult[DetailKind] = {
    val itemComparisons         = extraction.itemComparisons(responseExpected, responseActual)
    val answerDetailComparisons = extraction.answerDetailComparisons(responseExpected, responseActual)
    val detailComparisons       = itemComparisons.flatMap(itemDetailComparisons) ++ answerDetailComparisons
    val rightCount              = detailComparisons.count(_.outcome == DetailOutcome.Matched)

    GoldenPerformanceResult(
      rightCount = rightCount,
      countedCount = detailComparisons.length,
      overallPercentage = percentageRight(rightCount, detailComparisons.length),
      itemTally = ItemTally(
        paired = itemComparisons.count(_.pairingOutcome == PairingOutcome.Paired),
        missing = itemComparisons.count(_.pairingOutcome == PairingOutcome.Missing),
        extra = itemComparisons.count(_.pairingOutcome == PairingOutcome.Extra),
      ),
      detailKindTallies = extraction.detailKinds.map { detailKind =>
        val detailComparisonsOfKind = detailComparisons.filter(_.detailKind == detailKind)

        DetailKindTally(
          detailKind = detailKind,
          matched = detailComparisonsOfKind.count(_.outcome == DetailOutcome.Matched),
          mismatched = detailComparisonsOfKind.count(_.outcome == DetailOutcome.Mismatched),
          missing = detailComparisonsOfKind.count(_.outcome == DetailOutcome.Missing),
          extra = detailComparisonsOfKind.count(_.outcome == DetailOutcome.Extra),
          percentageRight = percentageRight(
            detailComparisonsOfKind.count(_.outcome == DetailOutcome.Matched),
            detailComparisonsOfKind.length,
          ),
        )
      },
      noteCountsAnswer = noteCounts(extraction, responseActual),
      noteCountsGolden = noteCounts(extraction, responseExpected),
      itemProblems = itemComparisons.flatMap(itemProblemsOpt(extraction.detailKinds, _)),
      answerDetailComparisonsNonMatched = sortedByDetailKind(
        extraction.detailKinds,
        answerDetailComparisons.filterNot(_.outcome == DetailOutcome.Matched),
      ),
    )
  }

  def renderReport[DetailKind <: GoldenPerformanceDetailKind](
      sourceFileName: String,
      modelName: String,
      result: GoldenPerformanceResult[DetailKind],
  ): String = {
    val summaryLines = List(
      s"$sourceFileName — model $modelName",
      s"Overall: ${result.overallPercentage}% right (${result.rightCount} of ${result.countedCount} details)",
      s"Items: ${result.itemTally.paired} paired, ${result.itemTally.missing} missing, ${result.itemTally.extra} extra",
    )
    val notesLine =
      s"Notes (not scored): ${result.noteCountsAnswer.general} general (golden ${result.noteCountsGolden.general}), " +
        s"${result.noteCountsAnswer.itemsWithNotes} items with notes (golden ${result.noteCountsGolden.itemsWithNotes}), " +
        s"${result.noteCountsAnswer.itemNotes} item notes (golden ${result.noteCountsGolden.itemNotes})"

    List(summaryLines, renderTableLines(result.detailKindTallies), List(notesLine), renderProblemsLines(result))
      .map(_.mkString(System.lineSeparator))
      .mkString(System.lineSeparator + System.lineSeparator)
  }
}
