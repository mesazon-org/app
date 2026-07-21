package io.mesazon.gateway.utils

import io.mesazon.gateway.repository.CustomerBookRepository.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.*
import org.scalacheck.*

trait RepositoryArbitraries
    extends GatewayArbitraries,
      OrganizationManagementDomainArbitraries,
      CustomerBookDomainArbitraries,
      IronRefinedTypeArbitraries {

  given Arbitrary[UserDetailsRow] = Arbitrary(Gen.resultOf(UserDetailsRow.apply))

  given Arbitrary[WahaUserRow] = Arbitrary(Gen.resultOf(WahaUserRow.apply))

  given Arbitrary[WahaUserActivityRow] = Arbitrary(Gen.resultOf(WahaUserActivityRow.apply))

  given Arbitrary[WahaUserMessageRow] = Arbitrary(Gen.resultOf(WahaUserMessageRow.apply))

  given Arbitrary[UserOtpRow] = Arbitrary(Gen.resultOf(UserOtpRow.apply))

  given Arbitrary[UserTokenRow] = Arbitrary(Gen.resultOf(UserTokenRow.apply))

  given Arbitrary[UserCredentialsRow] = Arbitrary(Gen.resultOf(UserCredentialsRow.apply))

  given Arbitrary[UserActionAttemptRow] = Arbitrary(Gen.resultOf(UserActionAttemptRow.apply))

  given Arbitrary[OrganizationDetailsRow] = Arbitrary(Gen.resultOf(OrganizationDetailsRow.apply))

  given Arbitrary[OrganizationUserRow] = Arbitrary(Gen.resultOf(OrganizationUserRow.apply))

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

  given Arbitrary[CustomerRow] = Arbitrary(Gen.resultOf(CustomerRow.apply))

  given Arbitrary[CustomerIndividualDetailsRow] = Arbitrary(Gen.resultOf(CustomerIndividualDetailsRow.apply))

  given Arbitrary[CustomerBusinessDetailsRow] = Arbitrary(Gen.resultOf(CustomerBusinessDetailsRow.apply))

  given Arbitrary[CustomerBusinessContactRow] = Arbitrary(Gen.resultOf(CustomerBusinessContactRow.apply))

  given Arbitrary[CustomerBusinessContactInput] = Arbitrary(Gen.resultOf(CustomerBusinessContactInput.apply))

  given Arbitrary[InsertCustomerIndividualInput] = Arbitrary(Gen.resultOf(InsertCustomerIndividualInput.apply))

  given Arbitrary[InsertCustomerBusinessInput] = Arbitrary(Gen.resultOf(InsertCustomerBusinessInput.apply))
}
