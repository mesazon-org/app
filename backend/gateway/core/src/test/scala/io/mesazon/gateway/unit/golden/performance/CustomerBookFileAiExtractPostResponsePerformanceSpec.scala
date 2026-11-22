package io.mesazon.gateway.unit.golden.performance

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.golden.performance.*
import io.mesazon.gateway.golden.performance.CustomerBookFileAiExtractPostResponseDetailKind.*
import io.mesazon.testkit.base.WordSpecBase

class CustomerBookFileAiExtractPostResponsePerformanceSpec extends WordSpecBase {

  private val customerIndividualCandidateMariaGolden = CustomerIndividualCandidate(
    fullName = Some(CustomerFullName.assume("Μαρία Παπαδοπούλου")),
    emails = List(CustomerEmailEntry(email = CustomerEmail.assume("maria@example.com"), isDefault = true)),
    phoneNumbers = List(
      CustomerPhoneNumberEntryCandidate(
        phoneNumber = CustomerPhoneNumberCandidate(
          phoneNationalNumber = PhoneNationalNumber.assume("99123456"),
          phoneCountryCode = Some(PhoneCountryCode.assume("357")),
        ),
        isDefault = true,
      )
    ),
    addresses = Nil,
    inferredFields = Nil,
    missingFields = Nil,
    duplicateFields = Nil,
    needsAttention = false,
    extractionNotes = Nil,
  )

  private val customerBusinessContactCandidateNikosGolden = CustomerBusinessContactCandidate(
    fullName = Some(CustomerFullName.assume("Νίκος Γεωργίου")),
    role = Some(CustomerBusinessContactRole.assume("Manager")),
    email = Some(CustomerEmail.assume("nikos@acme.example")),
    phoneNumber = None,
  )

  private val customerBusinessContactCandidateEleniGolden = CustomerBusinessContactCandidate(
    fullName = Some(CustomerFullName.assume("Ελένη Κώστα")),
    role = None,
    email = Some(CustomerEmail.assume("eleni@acme.example")),
    phoneNumber = None,
  )

  private val customerBusinessCandidateAcmeGolden = CustomerBusinessCandidate(
    businessName = Some(CustomerBusinessName.assume("Acme Ltd")),
    emails = Nil,
    taxID = Some(CustomerTaxID.assume("CY10203040X")),
    phoneNumbers = Nil,
    addresses = List(
      CustomerAddressEntry.assume(
        AddressEntry(
          address = Address(
            addressLine1 = Some(AddressLine1.assume("1 Main Street")),
            addressLine2 = None,
            city = Some(City.assume("Limassol")),
            postalCode = Some(PostalCode.assume("3030")),
            country = Some(Country.assume("Cyprus")),
          ),
          addressType = AddressType.Billing,
        )
      )
    ),
    customerBusinessContacts =
      List(customerBusinessContactCandidateNikosGolden, customerBusinessContactCandidateEleniGolden),
    inferredFields = List(FieldInfo("/customerBusinessContacts/1/fullName")),
    missingFields = Nil,
    duplicateFields = Nil,
    needsAttention = false,
    extractionNotes = Nil,
  )

  private val customerBookFileAiExtractPostResponseGolden = CustomerBookFileAiExtractPostResponse(
    individualCandidates = List(customerIndividualCandidateMariaGolden),
    businessCandidates = List(customerBusinessCandidateAcmeGolden),
    infoNotes = List("Processed 2 of 2 entries"),
    extractionNotes = Nil,
  )

  private val detailKindMatchedCountsGolden: Map[CustomerBookFileAiExtractPostResponseDetailKind, Int] = Map(
    IndividualFullName -> 1,
    BusinessName       -> 1,
    TaxID              -> 1,
    EmailAddress       -> 1,
    EmailDefault       -> 1,
    PhoneNational      -> 1,
    PhoneCountry       -> 1,
    PhoneDefault       -> 1,
    AddressFirstLine   -> 1,
    AddressCity        -> 1,
    AddressPostalCode  -> 1,
    AddressCountry     -> 1,
    AddressKind        -> 1,
    ContactFullName    -> 2,
    ContactRole        -> 1,
    ContactEmail       -> 2,
    AssumedMark        -> 1,
    NeedsAttention     -> 2,
    SummaryLine        -> 1,
  )

  private val detailKindTalliesGolden = CustomerBookFileAiExtractPostResponseDetailKind.values.toList.map(detailKind =>
    DetailKindTally(
      detailKind,
      matched = detailKindMatchedCountsGolden.getOrElse(detailKind, 0),
      mismatched = 0,
      missing = 0,
      extra = 0,
      percentageRight = BigDecimal("100.0"),
    )
  )

  private val goldenPerformanceResultGolden = GoldenPerformanceResult(
    rightCount = 22,
    countedCount = 22,
    overallPercentage = BigDecimal("100.0"),
    itemTally = ItemTally(paired = 2, missing = 0, extra = 0),
    detailKindTallies = detailKindTalliesGolden,
    noteCountsAnswer = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
    noteCountsGolden = NoteCounts(general = 0, itemsWithNotes = 0, itemNotes = 0),
    itemProblems = Nil,
    answerDetailComparisonsNonMatched = Nil,
  )

  "CustomerBookFileAiExtractPostResponsePerformance" when {
    "measure" should {
      "score 100% with no problems when the answer equals the golden" in {
        val customerBookFileAiExtractPostResponseActual = customerBookFileAiExtractPostResponseGolden

        val goldenPerformanceResult = GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
          customerBookFileAiExtractPostResponseGolden,
          customerBookFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden
      }

      "report a misread business name as a mismatch with its text closeness while the tax ID still pairs it" in {
        val customerBusinessCandidateAcmeActual =
          customerBusinessCandidateAcmeGolden.copy(businessName = Some(CustomerBusinessName.assume("Acme Ltd.")))
        val customerBookFileAiExtractPostResponseActual = customerBookFileAiExtractPostResponseGolden.copy(
          businessCandidates = List(customerBusinessCandidateAcmeActual)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
          customerBookFileAiExtractPostResponseGolden,
          customerBookFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden.copy(
          rightCount = 21,
          overallPercentage = BigDecimal("95.5"),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == BusinessName =>
              detailKindTally.copy(matched = 0, mismatched = 1, percentageRight = BigDecimal("0.0"))
            case detailKindTally => detailKindTally
          },
          itemProblems = List(
            ItemProblems(
              heading = "Business #1 Acme Ltd",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 14,
              detailComparisonsNonMatched = List(
                DetailComparison(
                  detailKind = BusinessName,
                  detail = "business name",
                  expectedOpt = Some("\"Acme Ltd\""),
                  actualOpt = Some("\"Acme Ltd.\""),
                  outcome = DetailOutcome.Mismatched,
                  closenessOpt = Some("1 character off, 89% alike"),
                )
              ),
              subItemProblems = Nil,
            )
          ),
        )
      }

      "report a skipped individual as missing and an unknown individual as extra with their detail counts" in {
        val customerIndividualCandidateGiorgosActual = CustomerIndividualCandidate(
          fullName = Some(CustomerFullName.assume("Γιώργος Νικολάου")),
          emails = List(CustomerEmailEntry(email = CustomerEmail.assume("giorgos@example.com"), isDefault = true)),
          phoneNumbers = Nil,
          addresses = Nil,
          inferredFields = Nil,
          missingFields = Nil,
          duplicateFields = Nil,
          needsAttention = false,
          extractionNotes = Nil,
        )
        val customerBookFileAiExtractPostResponseActual = customerBookFileAiExtractPostResponseGolden.copy(
          individualCandidates = List(customerIndividualCandidateGiorgosActual)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
          customerBookFileAiExtractPostResponseGolden,
          customerBookFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden.copy(
          rightCount = 15,
          countedCount = 26,
          overallPercentage = BigDecimal("57.7"),
          itemTally = ItemTally(paired = 1, missing = 1, extra = 1),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally
                if List(IndividualFullName, EmailAddress, EmailDefault).contains(detailKindTally.detailKind) =>
              detailKindTally.copy(matched = 0, missing = 1, extra = 1, percentageRight = BigDecimal("0.0"))
            case detailKindTally
                if List(PhoneNational, PhoneCountry, PhoneDefault).contains(detailKindTally.detailKind) =>
              detailKindTally.copy(matched = 0, missing = 1, percentageRight = BigDecimal("0.0"))
            case detailKindTally if detailKindTally.detailKind == NeedsAttention =>
              detailKindTally.copy(matched = 1, missing = 1, extra = 1, percentageRight = BigDecimal("33.3"))
            case detailKindTally => detailKindTally
          },
          itemProblems = List(
            ItemProblems(
              heading = "Individual #1 Μαρία Παπαδοπούλου",
              pairingOutcome = PairingOutcome.Missing,
              detailCount = 7,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            ),
            ItemProblems(
              heading = "individual \"Γιώργος Νικολάου\"",
              pairingOutcome = PairingOutcome.Extra,
              detailCount = 4,
              detailComparisonsNonMatched = Nil,
              subItemProblems = Nil,
            ),
          ),
        )
      }

      "report an assumed mark the golden does not have as extra" in {
        val customerIndividualCandidateMariaActual =
          customerIndividualCandidateMariaGolden.copy(inferredFields = List(FieldInfo("/fullName")))
        val customerBookFileAiExtractPostResponseActual = customerBookFileAiExtractPostResponseGolden.copy(
          individualCandidates = List(customerIndividualCandidateMariaActual)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
          customerBookFileAiExtractPostResponseGolden,
          customerBookFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden.copy(
          countedCount = 23,
          overallPercentage = BigDecimal("95.7"),
          detailKindTallies = detailKindTalliesGolden.map {
            case detailKindTally if detailKindTally.detailKind == AssumedMark =>
              detailKindTally.copy(extra = 1, percentageRight = BigDecimal("50.0"))
            case detailKindTally => detailKindTally
          },
          itemProblems = List(
            ItemProblems(
              heading = "Individual #1 Μαρία Παπαδοπούλου",
              pairingOutcome = PairingOutcome.Paired,
              detailCount = 8,
              detailComparisonsNonMatched = List(
                DetailComparison(
                  detailKind = AssumedMark,
                  detail = "assumed mark /fullName",
                  expectedOpt = None,
                  actualOpt = Some("present"),
                  outcome = DetailOutcome.Extra,
                  closenessOpt = None,
                )
              ),
              subItemProblems = Nil,
            )
          ),
        )
      }

      "pair reordered business contacts by email and remap their mark paths" in {
        val customerBusinessCandidateAcmeActual = customerBusinessCandidateAcmeGolden.copy(
          customerBusinessContacts =
            List(customerBusinessContactCandidateEleniGolden, customerBusinessContactCandidateNikosGolden),
          inferredFields = List(FieldInfo("/customerBusinessContacts/0/fullName")),
        )
        val customerBookFileAiExtractPostResponseActual = customerBookFileAiExtractPostResponseGolden.copy(
          businessCandidates = List(customerBusinessCandidateAcmeActual)
        )

        val goldenPerformanceResult = GoldenPerformance.measure(CustomerBookFileAiExtractPostResponsePerformance)(
          customerBookFileAiExtractPostResponseGolden,
          customerBookFileAiExtractPostResponseActual,
        )

        goldenPerformanceResult shouldBe goldenPerformanceResultGolden
      }
    }
  }
}
