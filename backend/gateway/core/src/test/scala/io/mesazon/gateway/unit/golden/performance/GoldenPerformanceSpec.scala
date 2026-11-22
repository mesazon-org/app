package io.mesazon.gateway.unit.golden.performance

import io.mesazon.gateway.golden.performance.*
import io.mesazon.gateway.golden.performance.CatalogueFileAiExtractPostResponseDetailKind.*
import io.mesazon.testkit.base.WordSpecBase

class GoldenPerformanceSpec extends WordSpecBase {

  "GoldenPerformance" when {
    "renderReport" should {
      "render the summary, the table without empty kinds, the note counts and every problem grouped by item" in {
        val goldenPerformanceResult = GoldenPerformanceResult(
          rightCount = 4,
          countedCount = 13,
          overallPercentage = BigDecimal("30.8"),
          itemTally = ItemTally(paired = 1, missing = 1, extra = 1),
          detailKindTallies = List(
            DetailKindTally(
              Name,
              matched = 1,
              mismatched = 1,
              missing = 1,
              extra = 1,
              percentageRight = BigDecimal("25.0"),
            ),
            DetailKindTally(
              Description,
              matched = 0,
              mismatched = 0,
              missing = 0,
              extra = 0,
              percentageRight = BigDecimal("100.0"),
            ),
            DetailKindTally(
              VariantLabel,
              matched = 1,
              mismatched = 0,
              missing = 1,
              extra = 0,
              percentageRight = BigDecimal("50.0"),
            ),
            DetailKindTally(
              VariantPrice,
              matched = 2,
              mismatched = 1,
              missing = 1,
              extra = 1,
              percentageRight = BigDecimal("40.0"),
            ),
            DetailKindTally(
              AssumedMark,
              matched = 0,
              mismatched = 0,
              missing = 0,
              extra = 1,
              percentageRight = BigDecimal("0.0"),
            ),
            DetailKindTally(
              SummaryLine,
              matched = 0,
              mismatched = 1,
              missing = 0,
              extra = 0,
              percentageRight = BigDecimal("0.0"),
            ),
          ),
          noteCountsAnswer = NoteCounts(general = 1, itemsWithNotes = 2, itemNotes = 3),
          noteCountsGolden = NoteCounts(general = 4, itemsWithNotes = 5, itemNotes = 6),
          itemProblems = List(
            ItemProblems(
              heading = "#1 Κραμπί",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 9,
              detailComparisonsNonMatched =
                List(
                  DetailComparison(
                    detailKind = Name,
                    detail = "name",
                    expectedOpt = Some("\"Κραμπί\""),
                    actualOpt = Some("\"Κραμπι\""),
                    outcome = DetailOutcome.Mismatched,
                    closenessOpt = Some("1 character off, 83% alike"),
                  ),
                  DetailComparison(
                    detailKind = AssumedMark,
                    detail = "assumed mark /description",
                    expectedOpt = None,
                    actualOpt = Some("present"),
                    outcome = DetailOutcome.Extra,
                    closenessOpt = None,
                  ),
                ),
              subItemProblems = List(
                SubItemProblems(
                  heading = "variant #1 SKU \"KR-1\"",
                  pairingOutcome = PairingOutcome.Paired,
                  detailCount = 4,
                  detailComparisonsNonMatched = List(
                    DetailComparison(
                      detailKind = VariantLabel,
                      detail = "label",
                      expectedOpt = Some("\"500 g\""),
                      actualOpt = None,
                      outcome = DetailOutcome.Missing,
                      closenessOpt = None,
                    ),
                    DetailComparison(
                      detailKind = VariantPrice,
                      detail = "price",
                      expectedOpt = Some("8.4"),
                      actualOpt = Some("8.1"),
                      outcome = DetailOutcome.Mismatched,
                      closenessOpt = None,
                    ),
                  ),
                ),
                SubItemProblems(
                  heading = "variant #2",
                  pairingOutcome = PairingOutcome.Missing,
                  detailCount = 3,
                  detailComparisonsNonMatched = Nil,
                ),
                SubItemProblems(
                  heading = "variant #3 SKU \"KR-3\"",
                  pairingOutcome = PairingOutcome.Extra,
                  detailCount = 4,
                  detailComparisonsNonMatched = Nil,
                ),
              ),
            ),
            ItemProblems(
              heading = "#2 Σέλινο",
              pairingOutcome = PairingOutcome.Missing,
              detailCount = 5,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            ),
            ItemProblems(
              heading = "\"Μαρούλι\"",
              pairingOutcome = PairingOutcome.Extra,
              detailCount = 1,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            ),
          ),
          answerDetailComparisonsNonMatched = List(
            DetailComparison(
              detailKind = SummaryLine,
              detail = "Processed",
              expectedOpt = Some("\"Processed 3 of 3 entries\""),
              actualOpt = Some("\"Processed 2 of 3 entries\""),
              outcome = DetailOutcome.Mismatched,
              closenessOpt = Some("1 character off, 96% alike"),
            )
          ),
        )

        val report =
          GoldenPerformance.renderReport("image-1.jpeg", "gpt-6.1-sol", goldenPerformanceResult)

        report.linesIterator.toList shouldBe List(
          "image-1.jpeg — model gpt-6.1-sol",
          "Overall: 30.8% right (4 of 13 details)",
          "Items: 1 paired, 1 missing, 1 extra",
          "",
          "Detail         Right Wrong Missing Extra  % right",
          "Name               1     1       1     1    25.0%",
          "Variant label      1     0       1     0    50.0%",
          "Price              2     1       1     1    40.0%",
          "Assumed mark       0     0       0     1     0.0%",
          "Summary line       0     1       0     0     0.0%",
          "",
          "Notes (not scored): 1 general (golden 4), 2 items with notes (golden 5), 3 item notes (golden 6)",
          "",
          "Problems",
          "#1 Κραμπί",
          "  name                       expected \"Κραμπί\", got \"Κραμπι\"",
          "                             (1 character off, 83% alike)",
          "  assumed mark /description  extra in the answer (got present)",
          "  variant #1 SKU \"KR-1\"",
          "    label                      missing from the answer (expected \"500 g\")",
          "    price                      expected 8.4, got 8.1",
          "  variant #2 — missing from the answer (3 details)",
          "  variant #3 SKU \"KR-3\" — extra in the answer (4 details)",
          "#2 Σέλινο — missing from the answer (5 details)",
          "Extra item \"Μαρούλι\" (1 detail)",
          "Summary lines",
          "  Processed                  expected \"Processed 3 of 3 entries\", got \"Processed 2 of 3 entries\"",
          "                             (1 character off, 96% alike)",
        )
      }

      "render Problems: none when nothing differs" in {
        val goldenPerformanceResult = GoldenPerformanceResult(
          rightCount = 2,
          countedCount = 2,
          overallPercentage = BigDecimal("100.0"),
          itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
          detailKindTallies = List(
            DetailKindTally(
              Name,
              matched = 2,
              mismatched = 0,
              missing = 0,
              extra = 0,
              percentageRight = BigDecimal("100.0"),
            ),
            DetailKindTally(
              Description,
              matched = 0,
              mismatched = 0,
              missing = 0,
              extra = 0,
              percentageRight = BigDecimal("100.0"),
            ),
          ),
          noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
          itemProblems = Nil,
          answerDetailComparisonsNonMatched = Nil,
        )

        val report =
          GoldenPerformance.renderReport("image-2.jpeg", "gpt-6.1-sol", goldenPerformanceResult)

        report.linesIterator.toList shouldBe List(
          "image-2.jpeg — model gpt-6.1-sol",
          "Overall: 100.0% right (2 of 2 details)",
          "Items: 2 paired, 0 missing, 0 extra",
          "",
          "Detail  Right Wrong Missing Extra  % right",
          "Name        2     0       0     0   100.0%",
          "",
          "Notes (not scored): 0 general (golden 0), 0 items with notes (golden 0), 0 item notes (golden 0)",
          "",
          "Problems: none",
        )
      }
    }
  }
}
