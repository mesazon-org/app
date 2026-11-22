package io.mesazon.gateway.golden.performance

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.golden.performance.CatalogueFileAiExtractPostResponseDetailKind.*
import io.mesazon.gateway.golden.performance.GoldenPerformance.{IndexPair, PairingRule, PairingScore}

enum CatalogueFileAiExtractPostResponseDetailKind(val label: String, val problemDetail: String)
    extends GoldenPerformanceDetailKind {
  case Name               extends CatalogueFileAiExtractPostResponseDetailKind("Name", "name")
  case Description        extends CatalogueFileAiExtractPostResponseDetailKind("Description", "description")
  case VariantSku         extends CatalogueFileAiExtractPostResponseDetailKind("Variant SKU", "sku")
  case VariantLabel       extends CatalogueFileAiExtractPostResponseDetailKind("Variant label", "label")
  case VariantDescription extends CatalogueFileAiExtractPostResponseDetailKind("Variant description", "description")
  case VariantPrice       extends CatalogueFileAiExtractPostResponseDetailKind("Price", "price")
  case VariantCurrency    extends CatalogueFileAiExtractPostResponseDetailKind("Currency", "currency")
  case VariantSellingUnit extends CatalogueFileAiExtractPostResponseDetailKind("Selling unit", "selling unit")
  case VariantAttribute   extends CatalogueFileAiExtractPostResponseDetailKind("Attribute", "attribute")
  case AssumedMark        extends CatalogueFileAiExtractPostResponseDetailKind("Assumed mark", "assumed mark")
  case MissingMark        extends CatalogueFileAiExtractPostResponseDetailKind("Missing mark", "missing mark")
  case DuplicateMark      extends CatalogueFileAiExtractPostResponseDetailKind("Duplicate mark", "duplicate mark")
  case NeedsAttention     extends CatalogueFileAiExtractPostResponseDetailKind("Needs attention", "needs attention")
  case SummaryLine        extends CatalogueFileAiExtractPostResponseDetailKind("Summary line", "summary line")
}

object CatalogueFileAiExtractPostResponsePerformance
    extends GoldenPerformanceExtraction[
      CatalogueFileAiExtractPostResponse,
      CatalogueFileAiExtractPostResponseDetailKind,
    ] {

  private type CatalogueFileAiExtractPostResponseDetailComparison =
    DetailComparison[CatalogueFileAiExtractPostResponseDetailKind]

  private type CatalogueItemVariantCandidatePairing = (
      catalogueItemVariantCandidateHeading: String,
      catalogueItemVariantCandidateExpectedOpt: Option[CatalogueItemVariantCandidate],
      catalogueItemVariantCandidateActualOpt: Option[CatalogueItemVariantCandidate],
  )

  private type ActualIndexWithIndex = (actualIndex: Int, index: Int)

  private type SellingUnitPart = (unit: CatalogueItemVariantUnit, number: BigDecimal)

  private type InfoLineKey = (infoLineLabel: String, occurrence: Int)

  private type InfoLineWithKey = (infoLine: String, infoLineKey: InfoLineKey)

  private type InfoLinesWithKeyAccumulator =
    (occurrenceCountByInfoLineLabel: Map[String, Int], infoLinesWithKey: List[InfoLineWithKey])

  private type FieldInfoDetailKind = (
      detailKind: CatalogueFileAiExtractPostResponseDetailKind,
      fieldInfosOf: CatalogueItemCandidate => List[FieldInfo],
  )

  private inline val PositionOffset    = 1
  private inline val PairingScoreFlat  = 0
  private inline val SkusSharedNone    = 0
  private inline val OccurrenceFirst   = 0
  private inline val OccurrenceStep    = 1
  private inline val CompareToEqual    = 0
  private inline val NoNameHeading     = "(no name)"
  private inline val ItemPartSeparator = " "
  private inline val VariantWord       = "variant"
  private inline val PerUnitWord       = "per"
  private inline val UnitSizeWord      = "size"

  private val similarityThreshold = BigDecimal("0.7")

  private val variantPathRegex = "^/variants/(\\d+)(.*)$".r

  private val pairingScoreFlat: PairingScore =
    (primary = BigDecimal(PairingScoreFlat), secondary = BigDecimal(PairingScoreFlat))

  private val fieldInfoDetailKinds: List[FieldInfoDetailKind] = List(
    (detailKind = AssumedMark, fieldInfosOf = _.inferredFields),
    (detailKind = MissingMark, fieldInfosOf = _.missingFields),
    (detailKind = DuplicateMark, fieldInfosOf = _.duplicateFields),
  )

  private val catalogueItemCandidatePairingRules: List[PairingRule[CatalogueItemCandidate, CatalogueItemCandidate]] =
    List(
      (catalogueItemCandidateExpected, catalogueItemCandidateActual) => {
        val skusSharedCount =
          catalogueItemVariantSkus(catalogueItemCandidateExpected)
            .intersect(catalogueItemVariantSkus(catalogueItemCandidateActual))
            .size

        Option.when(skusSharedCount > SkusSharedNone)(
          (
            primary = BigDecimal(skusSharedCount),
            secondary = textSimilarityOpt(
              catalogueItemCandidateExpected.name.map(_.value),
              catalogueItemCandidateActual.name.map(_.value),
            ).getOrElse(BigDecimal(PairingScoreFlat)),
          )
        )
      },
      (catalogueItemCandidateExpected, catalogueItemCandidateActual) =>
        Option.when(
          catalogueItemCandidateExpected.name.isDefined &&
            catalogueItemCandidateExpected.name == catalogueItemCandidateActual.name
        )(pairingScoreFlat),
      (catalogueItemCandidateExpected, catalogueItemCandidateActual) =>
        textSimilarPairingScoreOpt(
          catalogueItemCandidateExpected.name.map(_.value),
          catalogueItemCandidateActual.name.map(_.value),
        ),
    )

  private val catalogueItemVariantCandidatePairingRules
      : List[PairingRule[CatalogueItemVariantCandidate, CatalogueItemVariantCandidate]] =
    List(
      (catalogueItemVariantCandidateExpected, catalogueItemVariantCandidateActual) =>
        Option.when(
          catalogueItemVariantCandidateExpected.sku.isDefined &&
            catalogueItemVariantCandidateExpected.sku == catalogueItemVariantCandidateActual.sku
        )(pairingScoreFlat),
      (catalogueItemVariantCandidateExpected, catalogueItemVariantCandidateActual) =>
        Option.when(
          catalogueItemVariantCandidateExpected.label.isDefined &&
            catalogueItemVariantCandidateExpected.label == catalogueItemVariantCandidateActual.label
        )(pairingScoreFlat),
      (catalogueItemVariantCandidateExpected, catalogueItemVariantCandidateActual) =>
        textSimilarPairingScoreOpt(
          catalogueItemVariantCandidateExpected.label.map(_.value),
          catalogueItemVariantCandidateActual.label.map(_.value),
        ),
    )

  val detailKinds: List[CatalogueFileAiExtractPostResponseDetailKind] =
    CatalogueFileAiExtractPostResponseDetailKind.values.toList

  private def catalogueItemVariantSkus(catalogueItemCandidate: CatalogueItemCandidate): Set[String] =
    catalogueItemCandidate.variants.flatMap(_.sku.map(_.value)).toSet

  private def textSimilarityOpt(textExpectedOpt: Option[String], textActualOpt: Option[String]): Option[BigDecimal] =
    for {
      textExpected <- textExpectedOpt
      textActual   <- textActualOpt
    } yield GoldenPerformance.textCloseness(textExpected, textActual).similarity

  private def textSimilarPairingScoreOpt(
      textExpectedOpt: Option[String],
      textActualOpt: Option[String],
  ): Option[PairingScore] =
    textSimilarityOpt(textExpectedOpt, textActualOpt)
      .filter(_ >= similarityThreshold)
      .map(similarity => (primary = similarity, secondary = BigDecimal(PairingScoreFlat)))

  private def pairingOutcome(isExpectedPresent: Boolean, isActualPresent: Boolean): PairingOutcome =
    if (isExpectedPresent && isActualPresent) PairingOutcome.Paired
    else if (isExpectedPresent) PairingOutcome.Missing
    else PairingOutcome.Extra

  private def catalogueItemCandidateHeading(
      position: Int,
      catalogueItemNameOpt: Option[CatalogueItemName],
  ): String =
    List(s"#${position + PositionOffset}", catalogueItemNameOpt.fold(NoNameHeading)(_.value))
      .mkString(ItemPartSeparator)

  private def catalogueItemCandidateExtraHeading(catalogueItemNameOpt: Option[CatalogueItemName]): String =
    catalogueItemNameOpt.fold(NoNameHeading)(catalogueItemName => GoldenPerformance.renderText(catalogueItemName.value))

  private def catalogueItemVariantCandidateHeading(
      position: Int,
      catalogueItemVariantSkuOpt: Option[CatalogueItemVariantSku],
  ): String =
    (List(VariantWord, s"#${position + PositionOffset}") ++
      catalogueItemVariantSkuOpt
        .map(catalogueItemVariantSku => s"SKU ${GoldenPerformance.renderText(catalogueItemVariantSku.value)}")
        .toList)
      .mkString(ItemPartSeparator)

  private def sellingUnitQuantityPart(catalogueItemVariantSaleUnit: CatalogueItemVariantSaleUnit): SellingUnitPart =
    (unit = catalogueItemVariantSaleUnit.quantity.unit, number = catalogueItemVariantSaleUnit.quantity.value.value)

  private def sellingUnitPerUnitPartOpt(
      catalogueItemVariantSaleUnit: CatalogueItemVariantSaleUnit
  ): Option[SellingUnitPart] =
    catalogueItemVariantSaleUnit.perUnit.map(catalogueItemVariantPerUnit =>
      (unit = catalogueItemVariantPerUnit.unit, number = catalogueItemVariantPerUnit.value.value)
    )

  private def sellingUnitUnitSizePartOpt(
      catalogueItemVariantSaleUnit: CatalogueItemVariantSaleUnit
  ): Option[SellingUnitPart] =
    catalogueItemVariantSaleUnit.unitSize.map(catalogueItemVariantUnitSize =>
      (unit = catalogueItemVariantUnitSize.unit, number = catalogueItemVariantUnitSize.value.value)
    )

  private def isSellingUnitPartEqual(
      sellingUnitPartExpectedOpt: Option[SellingUnitPart],
      sellingUnitPartActualOpt: Option[SellingUnitPart],
  ): Boolean =
    (sellingUnitPartExpectedOpt, sellingUnitPartActualOpt) match {
      case (Some(sellingUnitPartExpected), Some(sellingUnitPartActual)) =>
        sellingUnitPartExpected.unit == sellingUnitPartActual.unit &&
        sellingUnitPartExpected.number.bigDecimal.compareTo(sellingUnitPartActual.number.bigDecimal) == CompareToEqual
      case (None, None) => true
      case _            => false
    }

  private def isSellingUnitEqual(
      catalogueItemVariantSaleUnitExpected: CatalogueItemVariantSaleUnit,
      catalogueItemVariantSaleUnitActual: CatalogueItemVariantSaleUnit,
  ): Boolean =
    isSellingUnitPartEqual(
      Some(sellingUnitQuantityPart(catalogueItemVariantSaleUnitExpected)),
      Some(sellingUnitQuantityPart(catalogueItemVariantSaleUnitActual)),
    ) &&
      isSellingUnitPartEqual(
        sellingUnitPerUnitPartOpt(catalogueItemVariantSaleUnitExpected),
        sellingUnitPerUnitPartOpt(catalogueItemVariantSaleUnitActual),
      ) &&
      isSellingUnitPartEqual(
        sellingUnitUnitSizePartOpt(catalogueItemVariantSaleUnitExpected),
        sellingUnitUnitSizePartOpt(catalogueItemVariantSaleUnitActual),
      )

  private def renderSellingUnitPart(sellingUnitPart: SellingUnitPart): String =
    List(GoldenPerformance.renderNumber(sellingUnitPart.number), sellingUnitPart.unit.toString)
      .mkString(ItemPartSeparator)

  private def renderSellingUnit(catalogueItemVariantSaleUnit: CatalogueItemVariantSaleUnit): String =
    (renderSellingUnitPart(sellingUnitQuantityPart(catalogueItemVariantSaleUnit)) ::
      sellingUnitPerUnitPartOpt(catalogueItemVariantSaleUnit)
        .map(sellingUnitPart => List(PerUnitWord, renderSellingUnitPart(sellingUnitPart)).mkString(ItemPartSeparator))
        .toList ++
      sellingUnitUnitSizePartOpt(catalogueItemVariantSaleUnit)
        .map(sellingUnitPart => List(UnitSizeWord, renderSellingUnitPart(sellingUnitPart)).mkString(ItemPartSeparator))
        .toList).mkString(ItemPartSeparator)

  private def catalogueItemVariantAttributes(
      catalogueItemVariantCandidateOpt: Option[CatalogueItemVariantCandidate]
  ): Map[String, String] =
    catalogueItemVariantCandidateOpt.toList
      .flatMap(_.attributes.toList)
      .map((catalogueItemVariantAttributeName, catalogueItemVariantAttributeValue) =>
        catalogueItemVariantAttributeName.value -> catalogueItemVariantAttributeValue.value
      )
      .toMap

  private def catalogueItemVariantCandidateComparison(
      catalogueItemVariantCandidatePairing: CatalogueItemVariantCandidatePairing
  ): SubItemComparison[CatalogueFileAiExtractPostResponseDetailKind] = {
    val catalogueItemVariantCandidateExpectedOpt =
      catalogueItemVariantCandidatePairing.catalogueItemVariantCandidateExpectedOpt
    val catalogueItemVariantCandidateActualOpt =
      catalogueItemVariantCandidatePairing.catalogueItemVariantCandidateActualOpt
    val catalogueItemVariantAttributesExpected = catalogueItemVariantAttributes(
      catalogueItemVariantCandidateExpectedOpt
    )
    val catalogueItemVariantAttributesActual = catalogueItemVariantAttributes(catalogueItemVariantCandidateActualOpt)

    SubItemComparison(
      heading = catalogueItemVariantCandidatePairing.catalogueItemVariantCandidateHeading,
      pairingOutcome = pairingOutcome(
        isExpectedPresent = catalogueItemVariantCandidateExpectedOpt.isDefined,
        isActualPresent = catalogueItemVariantCandidateActualOpt.isDefined,
      ),
      detailComparisons = List(
        GoldenPerformance.compareText(
          VariantSku,
          VariantSku.problemDetail,
          catalogueItemVariantCandidateExpectedOpt.flatMap(_.sku.map(_.value)),
          catalogueItemVariantCandidateActualOpt.flatMap(_.sku.map(_.value)),
        ),
        GoldenPerformance.compareText(
          VariantLabel,
          VariantLabel.problemDetail,
          catalogueItemVariantCandidateExpectedOpt.flatMap(_.label.map(_.value)),
          catalogueItemVariantCandidateActualOpt.flatMap(_.label.map(_.value)),
        ),
        GoldenPerformance.compareText(
          VariantDescription,
          VariantDescription.problemDetail,
          catalogueItemVariantCandidateExpectedOpt.flatMap(_.description.map(_.value)),
          catalogueItemVariantCandidateActualOpt.flatMap(_.description.map(_.value)),
        ),
        GoldenPerformance.compareNumber(
          VariantPrice,
          VariantPrice.problemDetail,
          catalogueItemVariantCandidateExpectedOpt.flatMap(_.price.map(_.amount.value)),
          catalogueItemVariantCandidateActualOpt.flatMap(_.price.map(_.amount.value)),
        ),
        GoldenPerformance.compareValue(
          VariantCurrency,
          VariantCurrency.problemDetail,
          catalogueItemVariantCandidateExpectedOpt.flatMap(_.price.flatMap(_.currency)),
          catalogueItemVariantCandidateActualOpt.flatMap(_.price.flatMap(_.currency)),
          _ == _,
          _.toString,
        ),
        GoldenPerformance.compareValue(
          VariantSellingUnit,
          VariantSellingUnit.problemDetail,
          catalogueItemVariantCandidateExpectedOpt.flatMap(_.sellingUnit),
          catalogueItemVariantCandidateActualOpt.flatMap(_.sellingUnit),
          isSellingUnitEqual,
          renderSellingUnit,
        ),
      ).flatten ++
        (catalogueItemVariantAttributesExpected.keySet ++ catalogueItemVariantAttributesActual.keySet).toList.sorted
          .flatMap(catalogueItemVariantAttributeName =>
            GoldenPerformance.compareText(
              VariantAttribute,
              List(VariantAttribute.problemDetail, catalogueItemVariantAttributeName).mkString(ItemPartSeparator),
              catalogueItemVariantAttributesExpected.get(catalogueItemVariantAttributeName),
              catalogueItemVariantAttributesActual.get(catalogueItemVariantAttributeName),
            )
          ),
    )
  }

  private def fieldInfoPathRemapped(fieldInfo: FieldInfo, variantIndexRemapped: Map[Int, Int]): String =
    fieldInfo.path match {
      case variantPathRegex(variantIndex, pathRest) =>
        s"/variants/${variantIndexRemapped.getOrElse(variantIndex.toInt, variantIndex.toInt)}$pathRest"
      case path => path
    }

  private def fieldInfoComparisons(
      detailKind: CatalogueFileAiExtractPostResponseDetailKind,
      fieldInfoPathsExpected: Set[String],
      fieldInfoPathsActual: Set[String],
  ): List[CatalogueFileAiExtractPostResponseDetailComparison] =
    (fieldInfoPathsExpected ++ fieldInfoPathsActual).toList.sorted.flatMap(fieldInfoPath =>
      GoldenPerformance.comparePresence(
        detailKind,
        List(detailKind.problemDetail, fieldInfoPath).mkString(ItemPartSeparator),
        isExpectedPresent = fieldInfoPathsExpected.contains(fieldInfoPath),
        isActualPresent = fieldInfoPathsActual.contains(fieldInfoPath),
      )
    )

  private def catalogueItemCandidateComparison(
      catalogueItemCandidateHeading: String,
      catalogueItemCandidateExpectedOpt: Option[CatalogueItemCandidate],
      catalogueItemCandidateActualOpt: Option[CatalogueItemCandidate],
  ): ItemComparison[CatalogueFileAiExtractPostResponseDetailKind] = {
    val catalogueItemVariantCandidatesExpected   = catalogueItemCandidateExpectedOpt.toVector.flatMap(_.variants)
    val catalogueItemVariantCandidatesActual     = catalogueItemCandidateActualOpt.toVector.flatMap(_.variants)
    val catalogueItemVariantCandidatePairingBest = GoldenPerformance.pairBestMatch(
      catalogueItemVariantCandidatesExpected.toList,
      catalogueItemVariantCandidatesActual.toList,
      catalogueItemVariantCandidatePairingRules,
    )
    val catalogueItemVariantCandidatePairingInOrder = GoldenPerformance.pairInOrder(
      catalogueItemVariantCandidatePairingBest.expectedIndicesUnpaired,
      catalogueItemVariantCandidatePairingBest.actualIndicesUnpaired,
    )
    val catalogueItemVariantIndexPairs: List[IndexPair] =
      catalogueItemVariantCandidatePairingBest.indexPairs ++ catalogueItemVariantCandidatePairingInOrder.indexPairs
    val catalogueItemVariantActualIndicesExtraWithIndex: List[ActualIndexWithIndex] =
      catalogueItemVariantCandidatePairingInOrder.actualIndicesUnpaired.zipWithIndex
    val variantIndexRemapped =
      catalogueItemVariantIndexPairs.map(indexPair => indexPair.actualIndex -> indexPair.expectedIndex).toMap ++
        catalogueItemVariantActualIndicesExtraWithIndex.map(actualIndexWithIndex =>
          actualIndexWithIndex.actualIndex -> (catalogueItemVariantCandidatesExpected.length + actualIndexWithIndex.index)
        )
    val catalogueItemVariantCandidatePairings: List[CatalogueItemVariantCandidatePairing] =
      catalogueItemVariantCandidatesExpected.indices.toList.map(expectedIndex =>
        (
          catalogueItemVariantCandidateHeading = catalogueItemVariantCandidateHeading(
            expectedIndex,
            catalogueItemVariantCandidatesExpected(expectedIndex).sku,
          ),
          catalogueItemVariantCandidateExpectedOpt = Some(catalogueItemVariantCandidatesExpected(expectedIndex)),
          catalogueItemVariantCandidateActualOpt = catalogueItemVariantIndexPairs
            .find(_.expectedIndex == expectedIndex)
            .map(indexPair => catalogueItemVariantCandidatesActual(indexPair.actualIndex)),
        )
      ) ++ catalogueItemVariantActualIndicesExtraWithIndex.map(actualIndexWithIndex =>
        (
          catalogueItemVariantCandidateHeading = catalogueItemVariantCandidateHeading(
            actualIndexWithIndex.actualIndex,
            catalogueItemVariantCandidatesActual(actualIndexWithIndex.actualIndex).sku,
          ),
          catalogueItemVariantCandidateExpectedOpt = None,
          catalogueItemVariantCandidateActualOpt =
            Some(catalogueItemVariantCandidatesActual(actualIndexWithIndex.actualIndex)),
        )
      )

    ItemComparison(
      heading = catalogueItemCandidateHeading,
      pairingOutcome = pairingOutcome(
        isExpectedPresent = catalogueItemCandidateExpectedOpt.isDefined,
        isActualPresent = catalogueItemCandidateActualOpt.isDefined,
      ),
      detailComparisons = List(
        GoldenPerformance.compareText(
          Name,
          Name.problemDetail,
          catalogueItemCandidateExpectedOpt.flatMap(_.name.map(_.value)),
          catalogueItemCandidateActualOpt.flatMap(_.name.map(_.value)),
        ),
        GoldenPerformance.compareText(
          Description,
          Description.problemDetail,
          catalogueItemCandidateExpectedOpt.flatMap(_.description.map(_.value)),
          catalogueItemCandidateActualOpt.flatMap(_.description.map(_.value)),
        ),
        GoldenPerformance.compareValue(
          NeedsAttention,
          NeedsAttention.problemDetail,
          catalogueItemCandidateExpectedOpt.map(_.needsAttention),
          catalogueItemCandidateActualOpt.map(_.needsAttention),
          _ == _,
          _.toString,
        ),
      ).flatten ++ fieldInfoDetailKinds.flatMap(fieldInfoDetailKind =>
        fieldInfoComparisons(
          fieldInfoDetailKind.detailKind,
          catalogueItemCandidateExpectedOpt.toList.flatMap(fieldInfoDetailKind.fieldInfosOf).map(_.path).toSet,
          catalogueItemCandidateActualOpt.toList
            .flatMap(fieldInfoDetailKind.fieldInfosOf)
            .map(fieldInfo => fieldInfoPathRemapped(fieldInfo, variantIndexRemapped))
            .toSet,
        )
      ),
      subItemComparisons = catalogueItemVariantCandidatePairings.map(catalogueItemVariantCandidateComparison),
    )
  }

  private def infoLinesWithKey(infoLines: List[String]): List[InfoLineWithKey] =
    infoLines
      .foldLeft[InfoLinesWithKeyAccumulator](
        (occurrenceCountByInfoLineLabel = Map.empty, infoLinesWithKey = Nil)
      ) { (infoLinesWithKeyAccumulator, infoLine) =>
        val infoLineLabel = infoLine.takeWhile(!_.isDigit).trim
        val occurrence    =
          infoLinesWithKeyAccumulator.occurrenceCountByInfoLineLabel.getOrElse(infoLineLabel, OccurrenceFirst)
        val infoLineWithKey: InfoLineWithKey =
          (infoLine = infoLine, infoLineKey = (infoLineLabel = infoLineLabel, occurrence = occurrence))

        (
          occurrenceCountByInfoLineLabel = infoLinesWithKeyAccumulator.occurrenceCountByInfoLineLabel
            .updated(infoLineLabel, occurrence + OccurrenceStep),
          infoLinesWithKey = infoLinesWithKeyAccumulator.infoLinesWithKey :+ infoLineWithKey,
        )
      }
      .infoLinesWithKey

  def itemComparisons(
      responseExpected: CatalogueFileAiExtractPostResponse,
      responseActual: CatalogueFileAiExtractPostResponse,
  ): List[ItemComparison[CatalogueFileAiExtractPostResponseDetailKind]] = {
    val catalogueItemCandidatesExpected = responseExpected.candidates.toVector
    val catalogueItemCandidatesActual   = responseActual.candidates.toVector
    val catalogueItemCandidatePairing   = GoldenPerformance.pairBestMatch(
      catalogueItemCandidatesExpected.toList,
      catalogueItemCandidatesActual.toList,
      catalogueItemCandidatePairingRules,
    )

    catalogueItemCandidatesExpected.indices.toList.map(expectedIndex =>
      catalogueItemCandidateComparison(
        catalogueItemCandidateHeading(expectedIndex, catalogueItemCandidatesExpected(expectedIndex).name),
        Some(catalogueItemCandidatesExpected(expectedIndex)),
        catalogueItemCandidatePairing.indexPairs
          .find(_.expectedIndex == expectedIndex)
          .map(indexPair => catalogueItemCandidatesActual(indexPair.actualIndex)),
      )
    ) ++ catalogueItemCandidatePairing.actualIndicesUnpaired.map(actualIndex =>
      catalogueItemCandidateComparison(
        catalogueItemCandidateExtraHeading(catalogueItemCandidatesActual(actualIndex).name),
        None,
        Some(catalogueItemCandidatesActual(actualIndex)),
      )
    )
  }

  def answerDetailComparisons(
      responseExpected: CatalogueFileAiExtractPostResponse,
      responseActual: CatalogueFileAiExtractPostResponse,
  ): List[CatalogueFileAiExtractPostResponseDetailComparison] = {
    val infoLinesWithKeyExpected = infoLinesWithKey(responseExpected.infoNotes)
    val infoLinesWithKeyActual   = infoLinesWithKey(responseActual.infoNotes)
    val infoLineByKeyActual      =
      infoLinesWithKeyActual.map(infoLineWithKey => infoLineWithKey.infoLineKey -> infoLineWithKey.infoLine).toMap
    val infoLineKeysExpected = infoLinesWithKeyExpected.map(_.infoLineKey).toSet

    infoLinesWithKeyExpected.flatMap(infoLineWithKeyExpected =>
      GoldenPerformance.compareText(
        SummaryLine,
        infoLineWithKeyExpected.infoLineKey.infoLineLabel,
        Some(infoLineWithKeyExpected.infoLine),
        infoLineByKeyActual.get(infoLineWithKeyExpected.infoLineKey),
      )
    ) ++ infoLinesWithKeyActual
      .filterNot(infoLineWithKeyActual => infoLineKeysExpected.contains(infoLineWithKeyActual.infoLineKey))
      .flatMap(infoLineWithKeyActual =>
        GoldenPerformance.compareText(
          SummaryLine,
          infoLineWithKeyActual.infoLineKey.infoLineLabel,
          None,
          Some(infoLineWithKeyActual.infoLine),
        )
      )
  }

  def generalNotes(response: CatalogueFileAiExtractPostResponse): List[String] =
    response.extractionNotes

  def itemNotes(response: CatalogueFileAiExtractPostResponse): List[List[String]] =
    response.candidates.map(_.extractionNotes)
}
