package io.mesazon.gateway.unit.golden.performance

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.golden.performance.*
import io.mesazon.gateway.golden.performance.CatalogueFileAiExtractPostResponseDetailKind.*
import io.mesazon.testkit.base.WordSpecBase

class CatalogueFileAiExtractPostResponsePerformanceSpec extends WordSpecBase {

  private val catalogueItemVariantPriceCandidateCabbageGolden = CatalogueItemVariantPriceCandidate(
    amount = PriceAmount.assume(BigDecimal("8.4")),
    currency = Some(Currency.EUR),
  )

  private val catalogueItemVariantCandidateCabbageGolden = CatalogueItemVariantCandidate(
    sku = None,
    label = None,
    description = None,
    attributes = Map.empty,
    price = Some(catalogueItemVariantPriceCandidateCabbageGolden),
    sellingUnit = Some(
      CatalogueItemVariantSaleUnit(
        quantity = CatalogueItemVariantQuantity(
          value = CatalogueItemVariantQuantityValue.assume(BigDecimal(12)),
          unit = CatalogueItemVariantUnit.UnitSize.Kilogram,
        ),
        perUnit = None,
        unitSize = None,
      )
    ),
  )

  private val catalogueItemCandidateCabbageGolden = CatalogueItemCandidate(
    name = Some(CatalogueItemName.assume("Κραμπί")),
    description = None,
    variants = List(catalogueItemVariantCandidateCabbageGolden),
    inferredFields = Nil,
    missingFields = Nil,
    duplicateFields = Nil,
    needsAttention = false,
    extractionNotes = Nil,
  )

  private val catalogueItemVariantCandidateTomatoGolden = CatalogueItemVariantCandidate(
    sku = Some(CatalogueItemVariantSku.assume("NT-1")),
    label = Some(CatalogueItemVariantLabel.assume("Α' ποιότητα")),
    description = None,
    attributes = Map(
      CatalogueItemVariantAttributeName.assume("Origin") -> CatalogueItemVariantAttributeValue.assume("Κρήτη")
    ),
    price = Some(
      CatalogueItemVariantPriceCandidate(
        amount = PriceAmount.assume(BigDecimal("2.5")),
        currency = Some(Currency.EUR),
      )
    ),
    sellingUnit = Some(
      CatalogueItemVariantSaleUnit(
        quantity = CatalogueItemVariantQuantity(
          value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
          unit = CatalogueItemVariantUnit.UnitSize.Kilogram,
        ),
        perUnit = None,
        unitSize = None,
      )
    ),
  )

  private val catalogueItemCandidateTomatoGolden = CatalogueItemCandidate(
    name = Some(CatalogueItemName.assume("Ντομάτα")),
    description = None,
    variants = List(catalogueItemVariantCandidateTomatoGolden),
    inferredFields = List(FieldInfo("/variants/0/price/currency")),
    missingFields = Nil,
    duplicateFields = Nil,
    needsAttention = false,
    extractionNotes = Nil,
  )

  private val catalogueFileAiExtractPostResponseGolden = CatalogueFileAiExtractPostResponse(
    candidates = List(catalogueItemCandidateCabbageGolden, catalogueItemCandidateTomatoGolden),
    infoNotes = List("Processed 2 of 2 entries"),
    extractionNotes = Nil,
  )

  private val detailKindTalliesGolden = List(
    DetailKindTally(Name, matched = 2, mismatched = 0, missing = 0, extra = 0, percentageRight = BigDecimal("100.0")),
    DetailKindTally(
      Description,
      matched = 0,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantSku,
      matched = 1,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantLabel,
      matched = 1,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantDescription,
      matched = 0,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantPrice,
      matched = 2,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantCurrency,
      matched = 2,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantSellingUnit,
      matched = 2,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      VariantAttribute,
      matched = 1,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      AssumedMark,
      matched = 1,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      MissingMark,
      matched = 0,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      DuplicateMark,
      matched = 0,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      NeedsAttention,
      matched = 2,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
    DetailKindTally(
      SummaryLine,
      matched = 1,
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    ),
  )

  "CatalogueFileAiExtractPostResponsePerformance" when {
    "measure" should {
      "score 100% with no problems when the answer equals the golden" in {
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 15,
          countedCount = 15,
          overallPercentage = BigDecimal("100.0"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = detailKindTalliesGolden,
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = Nil,
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "report one price mismatch with both values when the model misreads a price" in {
        val catalogueItemCandidateCabbageActual = catalogueItemCandidateCabbageGolden.copy(
          variants = List(
            catalogueItemVariantCandidateCabbageGolden.copy(
              price = Some(
                catalogueItemVariantPriceCandidateCabbageGolden.copy(amount = PriceAmount.assume(BigDecimal("8.1")))
              )
            )
          )
        )
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = List(catalogueItemCandidateCabbageActual, catalogueItemCandidateTomatoGolden)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 14,
          countedCount = 15,
          overallPercentage = BigDecimal("93.3"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == VariantPrice =>
              detailKindTally.copy(matched = 1, mismatched = 1, percentageRight = BigDecimal("50.0"))
            case detailKindTally => detailKindTally
          },
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = List(
            ItemProblems(
              heading = "#1 Κραμπί",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 5,
              detailComparisonsNonMatched = Nil,
              subItemProblems = List(
                SubItemProblems(
                  heading = "variant #1",
                  pairingOutcome = PairingOutcome.Paired,
                  detailCount = 3,
                  detailComparisonsNonMatched = List(
                    DetailComparison(
                      detailKind = VariantPrice,
                      detail = "price",
                      expectedOpt = Some("8.4"),
                      actualOpt = Some("8.1"),
                      outcome = DetailOutcome.Mismatched,
                      closenessOpt = None,
                    )
                  ),
                )
              ),
            )
          ),
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "report a skipped item as missing with its detail count while the later items still pair" in {
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = List(catalogueItemCandidateTomatoGolden)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 10,
          countedCount = 15,
          overallPercentage = BigDecimal("66.7"),
          itemTally = ItemTally(paired = 1, missing = 1, extra = 0),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally
                if List(Name, VariantPrice, VariantCurrency, VariantSellingUnit, NeedsAttention)
                  .contains(detailKindTally.detailKind) =>
              detailKindTally.copy(matched = 1, missing = 1, percentageRight = BigDecimal("50.0"))
            case detailKindTally => detailKindTally
          },
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = List(
            ItemProblems(
              heading = "#1 Κραμπί",
              pairingOutcome = PairingOutcome.Missing,
              detailCount = 5,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            )
          ),
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "report an item the golden does not have as extra with its detail count" in {
        val catalogueItemCandidateLettuceActual = CatalogueItemCandidate(
          name = Some(CatalogueItemName.assume("Μαρούλι")),
          description = None,
          variants = List(
            CatalogueItemVariantCandidate(
              sku = None,
              label = None,
              description = None,
              attributes = Map.empty,
              price = Some(
                CatalogueItemVariantPriceCandidate(
                  amount = PriceAmount.assume(BigDecimal("0.8")),
                  currency = Some(Currency.EUR),
                )
              ),
              sellingUnit = Some(
                CatalogueItemVariantSaleUnit(
                  quantity = CatalogueItemVariantQuantity(
                    value = CatalogueItemVariantQuantityValue.assume(BigDecimal(1)),
                    unit = CatalogueItemVariantUnit.PerUnit.Piece,
                  ),
                  perUnit = None,
                  unitSize = None,
                )
              ),
            )
          ),
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = false,
          extractionNotes = Nil,
        )
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = catalogueFileAiExtractPostResponseGolden.candidates :+ catalogueItemCandidateLettuceActual
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 15,
          countedCount = 20,
          overallPercentage = BigDecimal("75.0"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 1),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally
                if List(Name, VariantPrice, VariantCurrency, VariantSellingUnit, NeedsAttention)
                  .contains(detailKindTally.detailKind) =>
              detailKindTally.copy(extra = 1, percentageRight = BigDecimal("66.7"))
            case detailKindTally => detailKindTally
          },
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = List(
            ItemProblems(
              heading = "\"Μαρούλι\"",
              pairingOutcome = PairingOutcome.Extra,
              detailCount = 5,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            )
          ),
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "report a description the golden does not have as extra" in {
        val catalogueItemCandidateTomatoActual = catalogueItemCandidateTomatoGolden.copy(
          description = Some(CatalogueItemDescription.assume("Φρέσκια"))
        )
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = List(catalogueItemCandidateCabbageGolden, catalogueItemCandidateTomatoActual)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 15,
          countedCount = 16,
          overallPercentage = BigDecimal("93.8"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == Description =>
              detailKindTally.copy(extra = 1, percentageRight = BigDecimal("0.0"))
            case detailKindTally => detailKindTally
          },
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = List(
            ItemProblems(
              heading = "#2 Ντομάτα",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 10,
              detailComparisonsNonMatched = List(
                DetailComparison(
                  detailKind = Description,
                  detail = "description",
                  expectedOpt = None,
                  actualOpt = Some("\"Φρέσκια\""),
                  outcome = DetailOutcome.Extra,
                  closenessOpt = None,
                )
              ),
              subItemProblems = Nil,
            )
          ),
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "report a dropped accent in a name as a mismatch with its text closeness" in {
        val catalogueItemCandidateCabbageActual = catalogueItemCandidateCabbageGolden.copy(
          name = Some(CatalogueItemName.assume("Κραμπι"))
        )
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = List(catalogueItemCandidateCabbageActual, catalogueItemCandidateTomatoGolden)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 14,
          countedCount = 15,
          overallPercentage = BigDecimal("93.3"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == Name =>
              detailKindTally.copy(matched = 1, mismatched = 1, percentageRight = BigDecimal("50.0"))
            case detailKindTally => detailKindTally
          },
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = List(
            ItemProblems(
              heading = "#1 Κραμπί",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 5,
              detailComparisonsNonMatched = List(
                DetailComparison(
                  detailKind = Name,
                  detail = "name",
                  expectedOpt = Some("\"Κραμπί\""),
                  actualOpt = Some("\"Κραμπι\""),
                  outcome = DetailOutcome.Mismatched,
                  closenessOpt = Some("1 character off, 83% alike"),
                )
              ),
              subItemProblems = Nil,
            )
          ),
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "match a price that differs from the golden only by a trailing zero" in {
        val priceAmountActual                   = PriceAmount.assume(BigDecimal("8.40"))
        val catalogueItemCandidateCabbageActual = catalogueItemCandidateCabbageGolden.copy(
          variants = List(
            catalogueItemVariantCandidateCabbageGolden.copy(
              price = Some(catalogueItemVariantPriceCandidateCabbageGolden.copy(amount = priceAmountActual))
            )
          )
        )
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = List(catalogueItemCandidateCabbageActual, catalogueItemCandidateTomatoGolden)
        )

        priceAmountActual.toString shouldNot equal(catalogueItemVariantPriceCandidateCabbageGolden.amount.toString)

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 15,
          countedCount = 15,
          overallPercentage = BigDecimal("100.0"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = detailKindTalliesGolden,
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = Nil,
          answerDetailComparisonsNonMatched = Nil,
        )
      }

      "score 100% and only count the notes when only the extraction notes differ" in {
        val catalogueFileAiExtractPostResponseActual = catalogueFileAiExtractPostResponseGolden.copy(
          candidates = List(
            catalogueItemCandidateCabbageGolden.copy(extractionNotes = List("Price may be per box")),
            catalogueItemCandidateTomatoGolden,
          ),
          extractionNotes = List("Some rows were hard to read"),
        )

        catalogueFileAiExtractPostResponseActual shouldNot equal(catalogueFileAiExtractPostResponseGolden)

        val goldenPerformanceResult = GoldenPerformance.measure(CatalogueFileAiExtractPostResponsePerformance)(
          catalogueFileAiExtractPostResponseGolden,
          catalogueFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe GoldenPerformanceResult(
          rightCount = 15,
          countedCount = 15,
          overallPercentage = BigDecimal("100.0"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = detailKindTalliesGolden,
          noteCountsAnswer = NoteCounts(general = 1, itemsWithNotes = 1, itemNotes = 1),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = Nil,
          answerDetailComparisonsNonMatched = Nil,
        )
      }
    }
  }
}
