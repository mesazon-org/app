package io.mesazon.gateway.golden.performance

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.golden.performance.CustomerBookFileAiExtractPostResponseDetailKind.*
import io.mesazon.gateway.golden.performance.GoldenPerformance.{IndexPair, PairingRule, PairingScore}

enum CustomerBookFileAiExtractPostResponseDetailKind(val label: String, val problemDetail: String)
    extends GoldenPerformanceDetailKind {
  case IndividualFullName extends CustomerBookFileAiExtractPostResponseDetailKind("Full name", "full name")
  case BusinessName       extends CustomerBookFileAiExtractPostResponseDetailKind("Business name", "business name")
  case TaxID              extends CustomerBookFileAiExtractPostResponseDetailKind("Tax ID", "tax ID")
  case EmailAddress       extends CustomerBookFileAiExtractPostResponseDetailKind("Email", "email")
  case EmailDefault       extends CustomerBookFileAiExtractPostResponseDetailKind("Email default", "default")
  case PhoneNational      extends CustomerBookFileAiExtractPostResponseDetailKind("Phone number", "number")
  case PhoneCountry       extends CustomerBookFileAiExtractPostResponseDetailKind("Phone country code", "country code")
  case PhoneDefault       extends CustomerBookFileAiExtractPostResponseDetailKind("Phone default", "default")
  case AddressFirstLine   extends CustomerBookFileAiExtractPostResponseDetailKind("Address line 1", "line 1")
  case AddressSecondLine  extends CustomerBookFileAiExtractPostResponseDetailKind("Address line 2", "line 2")
  case AddressCity        extends CustomerBookFileAiExtractPostResponseDetailKind("City", "city")
  case AddressPostalCode  extends CustomerBookFileAiExtractPostResponseDetailKind("Postal code", "postal code")
  case AddressCountry     extends CustomerBookFileAiExtractPostResponseDetailKind("Country", "country")
  case AddressKind        extends CustomerBookFileAiExtractPostResponseDetailKind("Address type", "type")
  case ContactFullName    extends CustomerBookFileAiExtractPostResponseDetailKind("Contact name", "name")
  case ContactRole        extends CustomerBookFileAiExtractPostResponseDetailKind("Contact role", "role")
  case ContactEmail       extends CustomerBookFileAiExtractPostResponseDetailKind("Contact email", "email")
  case ContactPhoneNumber
      extends CustomerBookFileAiExtractPostResponseDetailKind("Contact phone number", "phone number")
  case ContactPhoneCountryCode
      extends CustomerBookFileAiExtractPostResponseDetailKind("Contact phone country code", "phone country code")
  case AssumedMark    extends CustomerBookFileAiExtractPostResponseDetailKind("Assumed mark", "assumed mark")
  case MissingMark    extends CustomerBookFileAiExtractPostResponseDetailKind("Missing mark", "missing mark")
  case DuplicateMark  extends CustomerBookFileAiExtractPostResponseDetailKind("Duplicate mark", "duplicate mark")
  case NeedsAttention extends CustomerBookFileAiExtractPostResponseDetailKind("Needs attention", "needs attention")
  case SummaryLine    extends CustomerBookFileAiExtractPostResponseDetailKind("Summary line", "summary line")
}

object CustomerBookFileAiExtractPostResponsePerformance
    extends GoldenPerformanceExtraction[
      CustomerBookFileAiExtractPostResponse,
      CustomerBookFileAiExtractPostResponseDetailKind,
    ] {

  private type CustomerBookFileAiExtractPostResponseDetailComparison =
    DetailComparison[CustomerBookFileAiExtractPostResponseDetailKind]

  private type CustomerBookFileAiExtractPostResponseSubItemComparison =
    SubItemComparison[CustomerBookFileAiExtractPostResponseDetailKind]

  private type CustomerBookFileAiExtractPostResponseItemComparison =
    ItemComparison[CustomerBookFileAiExtractPostResponseDetailKind]

  private type ActualIndexWithIndex = (actualIndex: Int, index: Int)

  private type SubItemPairing[SubItem] = (
      subItemHeading: String,
      subItemExpectedOpt: Option[SubItem],
      subItemActualOpt: Option[SubItem],
  )

  private type SubItemComparisonsWithIndexRemapped = (
      subItemComparisons: List[CustomerBookFileAiExtractPostResponseSubItemComparison],
      indexRemapped: Map[Int, Int],
  )

  private type CandidateFieldInfos = (
      inferredFields: List[FieldInfo],
      missingFields: List[FieldInfo],
      duplicateFields: List[FieldInfo],
  )

  private type FieldInfoDetailKind = (
      detailKind: CustomerBookFileAiExtractPostResponseDetailKind,
      fieldInfosOf: CandidateFieldInfos => List[FieldInfo],
  )

  private type InfoLineKey = (infoLineLabel: String, occurrence: Int)

  private type InfoLineWithKey = (infoLine: String, infoLineKey: InfoLineKey)

  private type InfoLinesWithKeyAccumulator =
    (occurrenceCountByInfoLineLabel: Map[String, Int], infoLinesWithKey: List[InfoLineWithKey])

  private inline val PositionOffset       = 1
  private inline val PairingScoreFlat     = 0
  private inline val SharedNone           = 0
  private inline val OccurrenceFirst      = 0
  private inline val OccurrenceStep       = 1
  private inline val NoNameHeading        = "(no name)"
  private inline val ItemPartSeparator    = " "
  private inline val AddressPartSeparator = ", "
  private inline val IndividualHeading    = "Individual"
  private inline val BusinessHeading      = "Business"
  private inline val IndividualWord       = "individual"
  private inline val BusinessWord         = "business"
  private inline val EmailWord            = "email"
  private inline val PhoneWord            = "phone"
  private inline val AddressWord          = "address"
  private inline val ContactWord          = "contact"
  private inline val EmailsProperty       = "emails"
  private inline val PhoneNumbersProperty = "phoneNumbers"
  private inline val AddressesProperty    = "addresses"
  private inline val ContactsProperty     = "customerBusinessContacts"

  private val similarityThreshold = BigDecimal("0.7")

  private val listPathRegex = "^/([A-Za-z]+)/(\\d+)(.*)$".r

  private val pairingScoreFlat: PairingScore =
    (primary = BigDecimal(PairingScoreFlat), secondary = BigDecimal(PairingScoreFlat))

  private val fieldInfoDetailKinds: List[FieldInfoDetailKind] = List(
    (detailKind = AssumedMark, fieldInfosOf = _.inferredFields),
    (detailKind = MissingMark, fieldInfosOf = _.missingFields),
    (detailKind = DuplicateMark, fieldInfosOf = _.duplicateFields),
  )

  private val customerIndividualCandidatePairingRules
      : List[PairingRule[CustomerIndividualCandidate, CustomerIndividualCandidate]] =
    List(
      (customerIndividualCandidateExpected, customerIndividualCandidateActual) =>
        sharedPairingScoreOpt(
          customerEmails(customerIndividualCandidateExpected.emails),
          customerEmails(customerIndividualCandidateActual.emails),
          customerIndividualCandidateExpected.fullName.map(_.value),
          customerIndividualCandidateActual.fullName.map(_.value),
        ),
      (customerIndividualCandidateExpected, customerIndividualCandidateActual) =>
        sharedPairingScoreOpt(
          phoneNationalNumbers(customerIndividualCandidateExpected.phoneNumbers),
          phoneNationalNumbers(customerIndividualCandidateActual.phoneNumbers),
          customerIndividualCandidateExpected.fullName.map(_.value),
          customerIndividualCandidateActual.fullName.map(_.value),
        ),
      (customerIndividualCandidateExpected, customerIndividualCandidateActual) =>
        exactPairingScoreOpt(
          customerIndividualCandidateExpected.fullName.map(_.value),
          customerIndividualCandidateActual.fullName.map(_.value),
        ),
      (customerIndividualCandidateExpected, customerIndividualCandidateActual) =>
        textSimilarPairingScoreOpt(
          customerIndividualCandidateExpected.fullName.map(_.value),
          customerIndividualCandidateActual.fullName.map(_.value),
        ),
    )

  private val customerBusinessCandidatePairingRules
      : List[PairingRule[CustomerBusinessCandidate, CustomerBusinessCandidate]] =
    List(
      (customerBusinessCandidateExpected, customerBusinessCandidateActual) =>
        exactPairingScoreOpt(
          customerBusinessCandidateExpected.taxID.map(_.value),
          customerBusinessCandidateActual.taxID.map(_.value),
        ).map(_ =>
          (
            primary = BigDecimal(PairingScoreFlat),
            secondary = textSimilarityOpt(
              customerBusinessCandidateExpected.businessName.map(_.value),
              customerBusinessCandidateActual.businessName.map(_.value),
            ).getOrElse(BigDecimal(PairingScoreFlat)),
          )
        ),
      (customerBusinessCandidateExpected, customerBusinessCandidateActual) =>
        sharedPairingScoreOpt(
          customerEmails(customerBusinessCandidateExpected.emails),
          customerEmails(customerBusinessCandidateActual.emails),
          customerBusinessCandidateExpected.businessName.map(_.value),
          customerBusinessCandidateActual.businessName.map(_.value),
        ),
      (customerBusinessCandidateExpected, customerBusinessCandidateActual) =>
        sharedPairingScoreOpt(
          phoneNationalNumbers(customerBusinessCandidateExpected.phoneNumbers),
          phoneNationalNumbers(customerBusinessCandidateActual.phoneNumbers),
          customerBusinessCandidateExpected.businessName.map(_.value),
          customerBusinessCandidateActual.businessName.map(_.value),
        ),
      (customerBusinessCandidateExpected, customerBusinessCandidateActual) =>
        exactPairingScoreOpt(
          customerBusinessCandidateExpected.businessName.map(_.value),
          customerBusinessCandidateActual.businessName.map(_.value),
        ),
      (customerBusinessCandidateExpected, customerBusinessCandidateActual) =>
        textSimilarPairingScoreOpt(
          customerBusinessCandidateExpected.businessName.map(_.value),
          customerBusinessCandidateActual.businessName.map(_.value),
        ),
    )

  private val customerEmailEntryPairingRules: List[PairingRule[CustomerEmailEntry, CustomerEmailEntry]] = List(
    (customerEmailEntryExpected, customerEmailEntryActual) =>
      exactPairingScoreOpt(Some(customerEmailEntryExpected.email.value), Some(customerEmailEntryActual.email.value)),
    (customerEmailEntryExpected, customerEmailEntryActual) =>
      textSimilarPairingScoreOpt(
        Some(customerEmailEntryExpected.email.value),
        Some(customerEmailEntryActual.email.value),
      ),
  )

  private val customerPhoneNumberEntryCandidatePairingRules
      : List[PairingRule[CustomerPhoneNumberEntryCandidate, CustomerPhoneNumberEntryCandidate]] = List(
    (customerPhoneNumberEntryCandidateExpected, customerPhoneNumberEntryCandidateActual) =>
      exactPairingScoreOpt(
        Some(customerPhoneNumberEntryCandidateExpected.phoneNumber.phoneNationalNumber.value),
        Some(customerPhoneNumberEntryCandidateActual.phoneNumber.phoneNationalNumber.value),
      ),
    (customerPhoneNumberEntryCandidateExpected, customerPhoneNumberEntryCandidateActual) =>
      textSimilarPairingScoreOpt(
        Some(customerPhoneNumberEntryCandidateExpected.phoneNumber.phoneNationalNumber.value),
        Some(customerPhoneNumberEntryCandidateActual.phoneNumber.phoneNationalNumber.value),
      ),
  )

  private val customerAddressEntryPairingRules: List[PairingRule[CustomerAddressEntry, CustomerAddressEntry]] = List(
    (customerAddressEntryExpected, customerAddressEntryActual) =>
      exactPairingScoreOpt(
        Some(addressText(customerAddressEntryExpected)),
        Some(addressText(customerAddressEntryActual)),
      ),
    (customerAddressEntryExpected, customerAddressEntryActual) =>
      textSimilarPairingScoreOpt(
        Some(addressText(customerAddressEntryExpected)),
        Some(addressText(customerAddressEntryActual)),
      ),
  )

  private val customerBusinessContactCandidatePairingRules
      : List[PairingRule[CustomerBusinessContactCandidate, CustomerBusinessContactCandidate]] = List(
    (customerBusinessContactCandidateExpected, customerBusinessContactCandidateActual) =>
      exactPairingScoreOpt(
        customerBusinessContactCandidateExpected.email.map(_.value),
        customerBusinessContactCandidateActual.email.map(_.value),
      ),
    (customerBusinessContactCandidateExpected, customerBusinessContactCandidateActual) =>
      exactPairingScoreOpt(
        customerBusinessContactCandidateExpected.phoneNumber.map(_.phoneNationalNumber.value),
        customerBusinessContactCandidateActual.phoneNumber.map(_.phoneNationalNumber.value),
      ),
    (customerBusinessContactCandidateExpected, customerBusinessContactCandidateActual) =>
      exactPairingScoreOpt(
        customerBusinessContactCandidateExpected.fullName.map(_.value),
        customerBusinessContactCandidateActual.fullName.map(_.value),
      ),
    (customerBusinessContactCandidateExpected, customerBusinessContactCandidateActual) =>
      textSimilarPairingScoreOpt(
        customerBusinessContactCandidateExpected.fullName.map(_.value),
        customerBusinessContactCandidateActual.fullName.map(_.value),
      ),
  )

  val detailKinds: List[CustomerBookFileAiExtractPostResponseDetailKind] =
    CustomerBookFileAiExtractPostResponseDetailKind.values.toList

  private def customerEmails(customerEmailEntries: List[CustomerEmailEntry]): Set[String] =
    customerEmailEntries.map(_.email.value).toSet

  private def phoneNationalNumbers(
      customerPhoneNumberEntryCandidates: List[CustomerPhoneNumberEntryCandidate]
  ): Set[String] =
    customerPhoneNumberEntryCandidates.map(_.phoneNumber.phoneNationalNumber.value).toSet

  private def addressText(customerAddressEntry: CustomerAddressEntry): String = {
    val address = customerAddressEntry.value.address

    List(
      address.addressLine1.map(_.value),
      address.addressLine2.map(_.value),
      address.city.map(_.value),
      address.postalCode.map(_.value),
      address.country.map(_.value),
    ).flatten.mkString(AddressPartSeparator)
  }

  private def textSimilarityOpt(textExpectedOpt: Option[String], textActualOpt: Option[String]): Option[BigDecimal] =
    for {
      textExpected <- textExpectedOpt
      textActual   <- textActualOpt
    } yield GoldenPerformance.textCloseness(textExpected, textActual).similarity

  private def exactPairingScoreOpt(
      textExpectedOpt: Option[String],
      textActualOpt: Option[String],
  ): Option[PairingScore] =
    Option.when(textExpectedOpt.isDefined && textExpectedOpt == textActualOpt)(pairingScoreFlat)

  private def sharedPairingScoreOpt(
      identifiersExpected: Set[String],
      identifiersActual: Set[String],
      nameExpectedOpt: Option[String],
      nameActualOpt: Option[String],
  ): Option[PairingScore] = {
    val identifiersSharedCount = identifiersExpected.intersect(identifiersActual).size

    Option.when(identifiersSharedCount > SharedNone)(
      (
        primary = BigDecimal(identifiersSharedCount),
        secondary = textSimilarityOpt(nameExpectedOpt, nameActualOpt).getOrElse(BigDecimal(PairingScoreFlat)),
      )
    )
  }

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

  private def candidateHeading(listHeading: String, position: Int, nameOpt: Option[String]): String =
    List(listHeading, s"#${position + PositionOffset}", nameOpt.getOrElse(NoNameHeading)).mkString(ItemPartSeparator)

  private def candidateExtraHeading(listWord: String, nameOpt: Option[String]): String =
    List(listWord, nameOpt.fold(NoNameHeading)(GoldenPerformance.renderText)).mkString(ItemPartSeparator)

  private def subItemHeading(subItemWord: String, position: Int, identifierOpt: Option[String]): String =
    (List(subItemWord, s"#${position + PositionOffset}") ++ identifierOpt.map(GoldenPerformance.renderText).toList)
      .mkString(ItemPartSeparator)

  private def subItemComparisonsWithIndexRemapped[SubItem](
      subItemsExpected: List[SubItem],
      subItemsActual: List[SubItem],
      pairingRules: List[PairingRule[SubItem, SubItem]],
      subItemHeadingOf: (Int, SubItem) => String,
      detailComparisonsOf: (Option[SubItem], Option[SubItem]) => List[
        CustomerBookFileAiExtractPostResponseDetailComparison
      ],
  ): SubItemComparisonsWithIndexRemapped = {
    val subItemsExpectedVector = subItemsExpected.toVector
    val subItemsActualVector   = subItemsActual.toVector
    val subItemPairingBest     = GoldenPerformance.pairBestMatch(subItemsExpected, subItemsActual, pairingRules)
    val subItemPairingInOrder  = GoldenPerformance.pairInOrder(
      subItemPairingBest.expectedIndicesUnpaired,
      subItemPairingBest.actualIndicesUnpaired,
    )
    val subItemIndexPairs: List[IndexPair] = subItemPairingBest.indexPairs ++ subItemPairingInOrder.indexPairs
    val subItemActualIndicesExtraWithIndex: List[ActualIndexWithIndex] =
      subItemPairingInOrder.actualIndicesUnpaired.zipWithIndex
    val indexRemapped =
      subItemIndexPairs.map(indexPair => indexPair.actualIndex -> indexPair.expectedIndex).toMap ++
        subItemActualIndicesExtraWithIndex.map(actualIndexWithIndex =>
          actualIndexWithIndex.actualIndex -> (subItemsExpectedVector.length + actualIndexWithIndex.index)
        )
    val subItemPairings: List[SubItemPairing[SubItem]] =
      subItemsExpectedVector.indices.toList.map(expectedIndex =>
        (
          subItemHeading = subItemHeadingOf(expectedIndex, subItemsExpectedVector(expectedIndex)),
          subItemExpectedOpt = Some(subItemsExpectedVector(expectedIndex)),
          subItemActualOpt = subItemIndexPairs
            .find(_.expectedIndex == expectedIndex)
            .map(indexPair => subItemsActualVector(indexPair.actualIndex)),
        )
      ) ++ subItemActualIndicesExtraWithIndex.map(actualIndexWithIndex =>
        (
          subItemHeading = subItemHeadingOf(
            actualIndexWithIndex.actualIndex,
            subItemsActualVector(actualIndexWithIndex.actualIndex),
          ),
          subItemExpectedOpt = None,
          subItemActualOpt = Some(subItemsActualVector(actualIndexWithIndex.actualIndex)),
        )
      )

    (
      subItemComparisons = subItemPairings.map(subItemPairing =>
        SubItemComparison(
          heading = subItemPairing.subItemHeading,
          pairingOutcome = pairingOutcome(
            isExpectedPresent = subItemPairing.subItemExpectedOpt.isDefined,
            isActualPresent = subItemPairing.subItemActualOpt.isDefined,
          ),
          detailComparisons = detailComparisonsOf(subItemPairing.subItemExpectedOpt, subItemPairing.subItemActualOpt),
        )
      ),
      indexRemapped = indexRemapped,
    )
  }

  private def customerEmailEntryDetailComparisons(
      customerEmailEntryExpectedOpt: Option[CustomerEmailEntry],
      customerEmailEntryActualOpt: Option[CustomerEmailEntry],
  ): List[CustomerBookFileAiExtractPostResponseDetailComparison] =
    List(
      GoldenPerformance.compareText(
        EmailAddress,
        EmailAddress.problemDetail,
        customerEmailEntryExpectedOpt.map(_.email.value),
        customerEmailEntryActualOpt.map(_.email.value),
      ),
      GoldenPerformance.compareValue(
        EmailDefault,
        EmailDefault.problemDetail,
        customerEmailEntryExpectedOpt.map(_.isDefault),
        customerEmailEntryActualOpt.map(_.isDefault),
        _ == _,
        _.toString,
      ),
    ).flatten

  private def customerPhoneNumberEntryCandidateDetailComparisons(
      customerPhoneNumberEntryCandidateExpectedOpt: Option[CustomerPhoneNumberEntryCandidate],
      customerPhoneNumberEntryCandidateActualOpt: Option[CustomerPhoneNumberEntryCandidate],
  ): List[CustomerBookFileAiExtractPostResponseDetailComparison] =
    List(
      GoldenPerformance.compareText(
        PhoneNational,
        PhoneNational.problemDetail,
        customerPhoneNumberEntryCandidateExpectedOpt.map(_.phoneNumber.phoneNationalNumber.value),
        customerPhoneNumberEntryCandidateActualOpt.map(_.phoneNumber.phoneNationalNumber.value),
      ),
      GoldenPerformance.compareText(
        PhoneCountry,
        PhoneCountry.problemDetail,
        customerPhoneNumberEntryCandidateExpectedOpt.flatMap(_.phoneNumber.phoneCountryCode.map(_.value)),
        customerPhoneNumberEntryCandidateActualOpt.flatMap(_.phoneNumber.phoneCountryCode.map(_.value)),
      ),
      GoldenPerformance.compareValue(
        PhoneDefault,
        PhoneDefault.problemDetail,
        customerPhoneNumberEntryCandidateExpectedOpt.map(_.isDefault),
        customerPhoneNumberEntryCandidateActualOpt.map(_.isDefault),
        _ == _,
        _.toString,
      ),
    ).flatten

  private def customerAddressEntryDetailComparisons(
      customerAddressEntryExpectedOpt: Option[CustomerAddressEntry],
      customerAddressEntryActualOpt: Option[CustomerAddressEntry],
  ): List[CustomerBookFileAiExtractPostResponseDetailComparison] = {
    val addressExpectedOpt = customerAddressEntryExpectedOpt.map(_.value.address)
    val addressActualOpt   = customerAddressEntryActualOpt.map(_.value.address)

    List(
      GoldenPerformance.compareText(
        AddressFirstLine,
        AddressFirstLine.problemDetail,
        addressExpectedOpt.flatMap(_.addressLine1.map(_.value)),
        addressActualOpt.flatMap(_.addressLine1.map(_.value)),
      ),
      GoldenPerformance.compareText(
        AddressSecondLine,
        AddressSecondLine.problemDetail,
        addressExpectedOpt.flatMap(_.addressLine2.map(_.value)),
        addressActualOpt.flatMap(_.addressLine2.map(_.value)),
      ),
      GoldenPerformance.compareText(
        AddressCity,
        AddressCity.problemDetail,
        addressExpectedOpt.flatMap(_.city.map(_.value)),
        addressActualOpt.flatMap(_.city.map(_.value)),
      ),
      GoldenPerformance.compareText(
        AddressPostalCode,
        AddressPostalCode.problemDetail,
        addressExpectedOpt.flatMap(_.postalCode.map(_.value)),
        addressActualOpt.flatMap(_.postalCode.map(_.value)),
      ),
      GoldenPerformance.compareText(
        AddressCountry,
        AddressCountry.problemDetail,
        addressExpectedOpt.flatMap(_.country.map(_.value)),
        addressActualOpt.flatMap(_.country.map(_.value)),
      ),
      GoldenPerformance.compareValue(
        AddressKind,
        AddressKind.problemDetail,
        customerAddressEntryExpectedOpt.map(_.value.addressType),
        customerAddressEntryActualOpt.map(_.value.addressType),
        _ == _,
        _.toString,
      ),
    ).flatten
  }

  private def customerBusinessContactCandidateDetailComparisons(
      customerBusinessContactCandidateExpectedOpt: Option[CustomerBusinessContactCandidate],
      customerBusinessContactCandidateActualOpt: Option[CustomerBusinessContactCandidate],
  ): List[CustomerBookFileAiExtractPostResponseDetailComparison] =
    List(
      GoldenPerformance.compareText(
        ContactFullName,
        ContactFullName.problemDetail,
        customerBusinessContactCandidateExpectedOpt.flatMap(_.fullName.map(_.value)),
        customerBusinessContactCandidateActualOpt.flatMap(_.fullName.map(_.value)),
      ),
      GoldenPerformance.compareText(
        ContactRole,
        ContactRole.problemDetail,
        customerBusinessContactCandidateExpectedOpt.flatMap(_.role.map(_.value)),
        customerBusinessContactCandidateActualOpt.flatMap(_.role.map(_.value)),
      ),
      GoldenPerformance.compareText(
        ContactEmail,
        ContactEmail.problemDetail,
        customerBusinessContactCandidateExpectedOpt.flatMap(_.email.map(_.value)),
        customerBusinessContactCandidateActualOpt.flatMap(_.email.map(_.value)),
      ),
      GoldenPerformance.compareText(
        ContactPhoneNumber,
        ContactPhoneNumber.problemDetail,
        customerBusinessContactCandidateExpectedOpt.flatMap(_.phoneNumber.map(_.phoneNationalNumber.value)),
        customerBusinessContactCandidateActualOpt.flatMap(_.phoneNumber.map(_.phoneNationalNumber.value)),
      ),
      GoldenPerformance.compareText(
        ContactPhoneCountryCode,
        ContactPhoneCountryCode.problemDetail,
        customerBusinessContactCandidateExpectedOpt.flatMap(_.phoneNumber.flatMap(_.phoneCountryCode.map(_.value))),
        customerBusinessContactCandidateActualOpt.flatMap(_.phoneNumber.flatMap(_.phoneCountryCode.map(_.value))),
      ),
    ).flatten

  private def fieldInfoPathRemapped(
      fieldInfo: FieldInfo,
      indexRemappedByListProperty: Map[String, Map[Int, Int]],
  ): String =
    fieldInfo.path match {
      case listPathRegex(listProperty, listIndex, pathRest) =>
        val listIndexRemapped =
          indexRemappedByListProperty.get(listProperty).flatMap(_.get(listIndex.toInt)).getOrElse(listIndex.toInt)

        s"/$listProperty/$listIndexRemapped$pathRest"
      case path => path
    }

  private def fieldInfoComparisons(
      candidateFieldInfosExpectedOpt: Option[CandidateFieldInfos],
      candidateFieldInfosActualOpt: Option[CandidateFieldInfos],
      indexRemappedByListProperty: Map[String, Map[Int, Int]],
  ): List[CustomerBookFileAiExtractPostResponseDetailComparison] =
    fieldInfoDetailKinds.flatMap { fieldInfoDetailKind =>
      val fieldInfoPathsExpected =
        candidateFieldInfosExpectedOpt.toList.flatMap(fieldInfoDetailKind.fieldInfosOf).map(_.path).toSet
      val fieldInfoPathsActual = candidateFieldInfosActualOpt.toList
        .flatMap(fieldInfoDetailKind.fieldInfosOf)
        .map(fieldInfo => fieldInfoPathRemapped(fieldInfo, indexRemappedByListProperty))
        .toSet

      (fieldInfoPathsExpected ++ fieldInfoPathsActual).toList.sorted.flatMap(fieldInfoPath =>
        GoldenPerformance.comparePresence(
          fieldInfoDetailKind.detailKind,
          List(fieldInfoDetailKind.detailKind.problemDetail, fieldInfoPath).mkString(ItemPartSeparator),
          isExpectedPresent = fieldInfoPathsExpected.contains(fieldInfoPath),
          isActualPresent = fieldInfoPathsActual.contains(fieldInfoPath),
        )
      )
    }

  private def customerEmailEntrySubItemComparisons(
      customerEmailEntriesExpected: List[CustomerEmailEntry],
      customerEmailEntriesActual: List[CustomerEmailEntry],
  ): SubItemComparisonsWithIndexRemapped =
    subItemComparisonsWithIndexRemapped(
      customerEmailEntriesExpected,
      customerEmailEntriesActual,
      customerEmailEntryPairingRules,
      (position, customerEmailEntry) => subItemHeading(EmailWord, position, Some(customerEmailEntry.email.value)),
      customerEmailEntryDetailComparisons,
    )

  private def customerPhoneNumberEntryCandidateSubItemComparisons(
      customerPhoneNumberEntryCandidatesExpected: List[CustomerPhoneNumberEntryCandidate],
      customerPhoneNumberEntryCandidatesActual: List[CustomerPhoneNumberEntryCandidate],
  ): SubItemComparisonsWithIndexRemapped =
    subItemComparisonsWithIndexRemapped(
      customerPhoneNumberEntryCandidatesExpected,
      customerPhoneNumberEntryCandidatesActual,
      customerPhoneNumberEntryCandidatePairingRules,
      (position, customerPhoneNumberEntryCandidate) =>
        subItemHeading(
          PhoneWord,
          position,
          Some(customerPhoneNumberEntryCandidate.phoneNumber.phoneNationalNumber.value),
        ),
      customerPhoneNumberEntryCandidateDetailComparisons,
    )

  private def customerAddressEntrySubItemComparisons(
      customerAddressEntriesExpected: List[CustomerAddressEntry],
      customerAddressEntriesActual: List[CustomerAddressEntry],
  ): SubItemComparisonsWithIndexRemapped =
    subItemComparisonsWithIndexRemapped(
      customerAddressEntriesExpected,
      customerAddressEntriesActual,
      customerAddressEntryPairingRules,
      (position, _) => subItemHeading(AddressWord, position, None),
      customerAddressEntryDetailComparisons,
    )

  private def customerBusinessContactCandidateSubItemComparisons(
      customerBusinessContactCandidatesExpected: List[CustomerBusinessContactCandidate],
      customerBusinessContactCandidatesActual: List[CustomerBusinessContactCandidate],
  ): SubItemComparisonsWithIndexRemapped =
    subItemComparisonsWithIndexRemapped(
      customerBusinessContactCandidatesExpected,
      customerBusinessContactCandidatesActual,
      customerBusinessContactCandidatePairingRules,
      (position, customerBusinessContactCandidate) =>
        subItemHeading(ContactWord, position, customerBusinessContactCandidate.fullName.map(_.value)),
      customerBusinessContactCandidateDetailComparisons,
    )

  private def customerIndividualCandidateComparison(
      customerIndividualCandidateHeading: String,
      customerIndividualCandidateExpectedOpt: Option[CustomerIndividualCandidate],
      customerIndividualCandidateActualOpt: Option[CustomerIndividualCandidate],
  ): CustomerBookFileAiExtractPostResponseItemComparison = {
    val customerEmailEntryComparisons = customerEmailEntrySubItemComparisons(
      customerIndividualCandidateExpectedOpt.toList.flatMap(_.emails),
      customerIndividualCandidateActualOpt.toList.flatMap(_.emails),
    )
    val customerPhoneNumberEntryCandidateComparisons = customerPhoneNumberEntryCandidateSubItemComparisons(
      customerIndividualCandidateExpectedOpt.toList.flatMap(_.phoneNumbers),
      customerIndividualCandidateActualOpt.toList.flatMap(_.phoneNumbers),
    )
    val customerAddressEntryComparisons = customerAddressEntrySubItemComparisons(
      customerIndividualCandidateExpectedOpt.toList.flatMap(_.addresses),
      customerIndividualCandidateActualOpt.toList.flatMap(_.addresses),
    )

    ItemComparison(
      heading = customerIndividualCandidateHeading,
      pairingOutcome = pairingOutcome(
        isExpectedPresent = customerIndividualCandidateExpectedOpt.isDefined,
        isActualPresent = customerIndividualCandidateActualOpt.isDefined,
      ),
      detailComparisons = List(
        GoldenPerformance.compareText(
          IndividualFullName,
          IndividualFullName.problemDetail,
          customerIndividualCandidateExpectedOpt.flatMap(_.fullName.map(_.value)),
          customerIndividualCandidateActualOpt.flatMap(_.fullName.map(_.value)),
        ),
        GoldenPerformance.compareValue(
          NeedsAttention,
          NeedsAttention.problemDetail,
          customerIndividualCandidateExpectedOpt.map(_.needsAttention),
          customerIndividualCandidateActualOpt.map(_.needsAttention),
          _ == _,
          _.toString,
        ),
      ).flatten ++ fieldInfoComparisons(
        customerIndividualCandidateExpectedOpt.map(customerIndividualCandidate =>
          (
            inferredFields = customerIndividualCandidate.inferredFields,
            missingFields = customerIndividualCandidate.missingFields,
            duplicateFields = customerIndividualCandidate.duplicateFields,
          )
        ),
        customerIndividualCandidateActualOpt.map(customerIndividualCandidate =>
          (
            inferredFields = customerIndividualCandidate.inferredFields,
            missingFields = customerIndividualCandidate.missingFields,
            duplicateFields = customerIndividualCandidate.duplicateFields,
          )
        ),
        Map(
          EmailsProperty       -> customerEmailEntryComparisons.indexRemapped,
          PhoneNumbersProperty -> customerPhoneNumberEntryCandidateComparisons.indexRemapped,
          AddressesProperty    -> customerAddressEntryComparisons.indexRemapped,
        ),
      ),
      subItemComparisons = customerEmailEntryComparisons.subItemComparisons ++
        customerPhoneNumberEntryCandidateComparisons.subItemComparisons ++
        customerAddressEntryComparisons.subItemComparisons,
    )
  }

  private def customerBusinessCandidateComparison(
      customerBusinessCandidateHeading: String,
      customerBusinessCandidateExpectedOpt: Option[CustomerBusinessCandidate],
      customerBusinessCandidateActualOpt: Option[CustomerBusinessCandidate],
  ): CustomerBookFileAiExtractPostResponseItemComparison = {
    val customerEmailEntryComparisons = customerEmailEntrySubItemComparisons(
      customerBusinessCandidateExpectedOpt.toList.flatMap(_.emails),
      customerBusinessCandidateActualOpt.toList.flatMap(_.emails),
    )
    val customerPhoneNumberEntryCandidateComparisons = customerPhoneNumberEntryCandidateSubItemComparisons(
      customerBusinessCandidateExpectedOpt.toList.flatMap(_.phoneNumbers),
      customerBusinessCandidateActualOpt.toList.flatMap(_.phoneNumbers),
    )
    val customerAddressEntryComparisons = customerAddressEntrySubItemComparisons(
      customerBusinessCandidateExpectedOpt.toList.flatMap(_.addresses),
      customerBusinessCandidateActualOpt.toList.flatMap(_.addresses),
    )
    val customerBusinessContactCandidateComparisons = customerBusinessContactCandidateSubItemComparisons(
      customerBusinessCandidateExpectedOpt.toList.flatMap(_.customerBusinessContacts),
      customerBusinessCandidateActualOpt.toList.flatMap(_.customerBusinessContacts),
    )

    ItemComparison(
      heading = customerBusinessCandidateHeading,
      pairingOutcome = pairingOutcome(
        isExpectedPresent = customerBusinessCandidateExpectedOpt.isDefined,
        isActualPresent = customerBusinessCandidateActualOpt.isDefined,
      ),
      detailComparisons = List(
        GoldenPerformance.compareText(
          BusinessName,
          BusinessName.problemDetail,
          customerBusinessCandidateExpectedOpt.flatMap(_.businessName.map(_.value)),
          customerBusinessCandidateActualOpt.flatMap(_.businessName.map(_.value)),
        ),
        GoldenPerformance.compareText(
          TaxID,
          TaxID.problemDetail,
          customerBusinessCandidateExpectedOpt.flatMap(_.taxID.map(_.value)),
          customerBusinessCandidateActualOpt.flatMap(_.taxID.map(_.value)),
        ),
        GoldenPerformance.compareValue(
          NeedsAttention,
          NeedsAttention.problemDetail,
          customerBusinessCandidateExpectedOpt.map(_.needsAttention),
          customerBusinessCandidateActualOpt.map(_.needsAttention),
          _ == _,
          _.toString,
        ),
      ).flatten ++ fieldInfoComparisons(
        customerBusinessCandidateExpectedOpt.map(customerBusinessCandidate =>
          (
            inferredFields = customerBusinessCandidate.inferredFields,
            missingFields = customerBusinessCandidate.missingFields,
            duplicateFields = customerBusinessCandidate.duplicateFields,
          )
        ),
        customerBusinessCandidateActualOpt.map(customerBusinessCandidate =>
          (
            inferredFields = customerBusinessCandidate.inferredFields,
            missingFields = customerBusinessCandidate.missingFields,
            duplicateFields = customerBusinessCandidate.duplicateFields,
          )
        ),
        Map(
          EmailsProperty       -> customerEmailEntryComparisons.indexRemapped,
          PhoneNumbersProperty -> customerPhoneNumberEntryCandidateComparisons.indexRemapped,
          AddressesProperty    -> customerAddressEntryComparisons.indexRemapped,
          ContactsProperty     -> customerBusinessContactCandidateComparisons.indexRemapped,
        ),
      ),
      subItemComparisons = customerEmailEntryComparisons.subItemComparisons ++
        customerPhoneNumberEntryCandidateComparisons.subItemComparisons ++
        customerAddressEntryComparisons.subItemComparisons ++
        customerBusinessContactCandidateComparisons.subItemComparisons,
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
      responseExpected: CustomerBookFileAiExtractPostResponse,
      responseActual: CustomerBookFileAiExtractPostResponse,
  ): List[CustomerBookFileAiExtractPostResponseItemComparison] = {
    val customerIndividualCandidatesExpected = responseExpected.individualCandidates.toVector
    val customerIndividualCandidatesActual   = responseActual.individualCandidates.toVector
    val customerBusinessCandidatesExpected   = responseExpected.businessCandidates.toVector
    val customerBusinessCandidatesActual     = responseActual.businessCandidates.toVector
    val customerIndividualCandidatePairing   = GoldenPerformance.pairBestMatch(
      customerIndividualCandidatesExpected.toList,
      customerIndividualCandidatesActual.toList,
      customerIndividualCandidatePairingRules,
    )
    val customerBusinessCandidatePairing = GoldenPerformance.pairBestMatch(
      customerBusinessCandidatesExpected.toList,
      customerBusinessCandidatesActual.toList,
      customerBusinessCandidatePairingRules,
    )

    customerIndividualCandidatesExpected.indices.toList.map(expectedIndex =>
      customerIndividualCandidateComparison(
        candidateHeading(
          IndividualHeading,
          expectedIndex,
          customerIndividualCandidatesExpected(expectedIndex).fullName.map(_.value),
        ),
        Some(customerIndividualCandidatesExpected(expectedIndex)),
        customerIndividualCandidatePairing.indexPairs
          .find(_.expectedIndex == expectedIndex)
          .map(indexPair => customerIndividualCandidatesActual(indexPair.actualIndex)),
      )
    ) ++ customerBusinessCandidatesExpected.indices.toList.map(expectedIndex =>
      customerBusinessCandidateComparison(
        candidateHeading(
          BusinessHeading,
          expectedIndex,
          customerBusinessCandidatesExpected(expectedIndex).businessName.map(_.value),
        ),
        Some(customerBusinessCandidatesExpected(expectedIndex)),
        customerBusinessCandidatePairing.indexPairs
          .find(_.expectedIndex == expectedIndex)
          .map(indexPair => customerBusinessCandidatesActual(indexPair.actualIndex)),
      )
    ) ++ customerIndividualCandidatePairing.actualIndicesUnpaired.map(actualIndex =>
      customerIndividualCandidateComparison(
        candidateExtraHeading(IndividualWord, customerIndividualCandidatesActual(actualIndex).fullName.map(_.value)),
        None,
        Some(customerIndividualCandidatesActual(actualIndex)),
      )
    ) ++ customerBusinessCandidatePairing.actualIndicesUnpaired.map(actualIndex =>
      customerBusinessCandidateComparison(
        candidateExtraHeading(BusinessWord, customerBusinessCandidatesActual(actualIndex).businessName.map(_.value)),
        None,
        Some(customerBusinessCandidatesActual(actualIndex)),
      )
    )
  }

  def answerDetailComparisons(
      responseExpected: CustomerBookFileAiExtractPostResponse,
      responseActual: CustomerBookFileAiExtractPostResponse,
  ): List[CustomerBookFileAiExtractPostResponseDetailComparison] = {
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

  def generalNotes(response: CustomerBookFileAiExtractPostResponse): List[String] =
    response.extractionNotes

  def itemNotes(response: CustomerBookFileAiExtractPostResponse): List[List[String]] =
    response.individualCandidates.map(_.extractionNotes) ++ response.businessCandidates.map(_.extractionNotes)
}
