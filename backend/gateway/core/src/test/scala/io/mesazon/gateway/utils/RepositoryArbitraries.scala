package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.repository.CatalogueRepository.*
import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.*
import org.scalacheck.*

trait RepositoryArbitraries
    extends GatewayArbitraries,
      OrganizationManagementDomainArbitraries,
      CustomerBookDomainArbitraries,
      IronRefinedTypeArbitraries {

  given arbCatalogueItemStatus: Arbitrary[CatalogueItemStatus] =
    Arbitrary(Gen.oneOf(CatalogueItemStatus.values.toIndexedSeq))

  given arbUserDetailsRow: Arbitrary[UserDetailsRow] = Arbitrary(Gen.resultOf(UserDetailsRow.apply))

  given arbWahaUserRow: Arbitrary[WahaUserRow] = Arbitrary(Gen.resultOf(WahaUserRow.apply))

  given arbWahaUserActivityRow: Arbitrary[WahaUserActivityRow] = Arbitrary(Gen.resultOf(WahaUserActivityRow.apply))

  given arbWahaUserMessageRow: Arbitrary[WahaUserMessageRow] = Arbitrary(Gen.resultOf(WahaUserMessageRow.apply))

  given arbUserOtpRow: Arbitrary[UserOtpRow] = Arbitrary(Gen.resultOf(UserOtpRow.apply))

  given arbUserTokenRow: Arbitrary[UserTokenRow] = Arbitrary(Gen.resultOf(UserTokenRow.apply))

  given arbUserCredentialsRow: Arbitrary[UserCredentialsRow] = Arbitrary(Gen.resultOf(UserCredentialsRow.apply))

  given arbUserActionAttemptRow: Arbitrary[UserActionAttemptRow] = Arbitrary(Gen.resultOf(UserActionAttemptRow.apply))

  given arbOrganizationDetailsRow: Arbitrary[OrganizationDetailsRow] =
    Arbitrary(Gen.resultOf(OrganizationDetailsRow.apply))

  given arbOrganizationUserRow: Arbitrary[OrganizationUserRow] = Arbitrary(Gen.resultOf(OrganizationUserRow.apply))

  given arbPrice: Arbitrary[Price] = Arbitrary(Gen.resultOf(Price.apply))

  given arbPhoto: Arbitrary[Photo] = Arbitrary(Gen.resultOf(Photo.apply))

  given arbCatalogueItemRow: Arbitrary[CatalogueItemRow] = Arbitrary(Gen.resultOf(CatalogueItemRow.apply))

  given arbInsertCatalogueItemInput: Arbitrary[InsertCatalogueItemInput] =
    Arbitrary(Gen.resultOf(InsertCatalogueItemInput.apply))

  given arbInsertCatalogueItemInputs: Arbitrary[List[InsertCatalogueItemInput]] = Arbitrary(
    Gen
      .choose(0, 50)
      .flatMap(numberOfCatalogueItems =>
        Gen.listOfN(numberOfCatalogueItems, Arbitrary.arbitrary[InsertCatalogueItemInput])
      )
  )

  given arbCustomerEmailEntryInput: Arbitrary[CustomerEmailEntryInput] = Arbitrary(
    Gen.resultOf(CustomerEmailEntryInput.apply)
  )

  given arbCustomerPhoneNumberEntryInput: Arbitrary[CustomerPhoneNumberEntryInput] = Arbitrary(
    Gen.resultOf(CustomerPhoneNumberEntryInput.apply)
  )

  given arbCustomerEmailEntryInputs: Arbitrary[List[CustomerEmailEntryInput]] = Arbitrary(
    genEntriesWithSingleDefault(
      arbCustomerEmailEntryInput.arbitrary.map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbCustomerPhoneNumberEntryInputs: Arbitrary[List[CustomerPhoneNumberEntryInput]] = Arbitrary(
    genEntriesWithSingleDefault(
      arbCustomerPhoneNumberEntryInput.arbitrary.map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbCustomerIndividualDetailsRow: Arbitrary[CustomerIndividualDetailsRow] =
    Arbitrary(Gen.resultOf(CustomerIndividualDetailsRow.apply))

  given arbCustomerBusinessDetailsRow: Arbitrary[CustomerBusinessDetailsRow] =
    Arbitrary(Gen.resultOf(CustomerBusinessDetailsRow.apply))

  given arbCustomerBusinessContactRow: Arbitrary[CustomerBusinessContactRow] =
    Arbitrary(Gen.resultOf(CustomerBusinessContactRow.apply))

  given arbCustomerSummaryRow: Arbitrary[CustomerSummaryRow] = Arbitrary(Gen.resultOf(CustomerSummaryRow.apply))

  given arbCustomerBusinessContactInput: Arbitrary[CustomerBusinessContactInput] =
    Arbitrary(Gen.resultOf(CustomerBusinessContactInput.apply))

  given arbInsertCustomerIndividualInput: Arbitrary[InsertCustomerIndividualInput] =
    Arbitrary(Gen.resultOf(InsertCustomerIndividualInput.apply))

  given arbInsertCustomerBusinessInput: Arbitrary[InsertCustomerBusinessInput] =
    Arbitrary(Gen.resultOf(InsertCustomerBusinessInput.apply))
}
