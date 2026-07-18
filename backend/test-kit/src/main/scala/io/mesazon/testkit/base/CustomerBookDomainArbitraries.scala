package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait CustomerBookDomainArbitraries extends GatewayArbitraries {

  given arbCustomerEmailEntry: Arbitrary[CustomerEmailEntry] = Arbitrary(Gen.resultOf(CustomerEmailEntry.apply))

  given arbCustomerPhoneNumberEntry: Arbitrary[CustomerPhoneNumberEntry] = Arbitrary(
    Gen.resultOf(CustomerPhoneNumberEntry.apply)
  )

  // Mark exactly one entry (the first) default in each non-empty list so generated lists satisfy the validator.
  given arbCustomerEmailEntries: Arbitrary[List[CustomerEmailEntry]] = Arbitrary(
    Gen
      .listOf(Arbitrary.arbitrary[CustomerEmailEntry])
      .map(markFirstAsDefault(_)((entry, isDefault) => entry.copy(isDefault = isDefault)))
  )

  given arbCustomerPhoneNumberEntries: Arbitrary[List[CustomerPhoneNumberEntry]] = Arbitrary(
    Gen
      .listOf(Arbitrary.arbitrary[CustomerPhoneNumberEntry])
      .map(markFirstAsDefault(_)((entry, isDefault) => entry.copy(isDefault = isDefault)))
  )

  private def markFirstAsDefault[A](entries: List[A])(setDefault: (A, Boolean) => A): List[A] =
    entries.zipWithIndex.map { case (entry, index) => setDefault(entry, index == 0) }

  given arbInsertCustomerIndividual: Arbitrary[InsertCustomerIndividual] = Arbitrary(
    Gen.resultOf(InsertCustomerIndividual.apply)
  )

  given arbInsertCustomerIndividuals: Arbitrary[InsertCustomerIndividuals] = Arbitrary(
    Gen.resultOf(InsertCustomerIndividuals.apply)
  )

  given arbUpdateCustomerIndividual: Arbitrary[UpdateCustomerIndividual] = Arbitrary(
    Gen.resultOf(UpdateCustomerIndividual.apply)
  )

  given arbInsertCustomerBusinessContact: Arbitrary[InsertCustomerBusinessContact] = Arbitrary(
    Gen.resultOf(InsertCustomerBusinessContact.apply)
  )

  given arbAddCustomerBusinessContact: Arbitrary[AddCustomerBusinessContact] = Arbitrary(
    Gen.resultOf(AddCustomerBusinessContact.apply)
  )

  given arbAddCustomerBusinessContacts: Arbitrary[AddCustomerBusinessContacts] = Arbitrary(
    Gen.resultOf(AddCustomerBusinessContacts.apply)
  )

  given arbInsertCustomerBusiness: Arbitrary[InsertCustomerBusiness] = Arbitrary(
    Gen.resultOf(InsertCustomerBusiness.apply)
  )

  given arbInsertCustomerBusinesses: Arbitrary[InsertCustomerBusinesses] = Arbitrary(
    Gen.resultOf(InsertCustomerBusinesses.apply)
  )

  given arbUpdateCustomerBusiness: Arbitrary[UpdateCustomerBusiness] = Arbitrary(
    Gen.resultOf(UpdateCustomerBusiness.apply)
  )

  given arbInsertCustomers: Arbitrary[InsertCustomers] = Arbitrary(Gen.resultOf(InsertCustomers.apply))
}
