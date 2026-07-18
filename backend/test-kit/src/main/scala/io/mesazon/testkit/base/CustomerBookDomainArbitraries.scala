package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait CustomerBookDomainArbitraries extends GatewayArbitraries {

  given arbCustomerEmailEntry: Arbitrary[CustomerEmailEntry] = Arbitrary(Gen.resultOf(CustomerEmailEntry.apply))

  given arbCustomerPhoneNumberEntry: Arbitrary[CustomerPhoneNumberEntry] = Arbitrary(
    Gen.resultOf(CustomerPhoneNumberEntry.apply)
  )

  // A non-empty list must mark exactly one entry as default, so generate non-default entries and promote one at random.
  given arbCustomerEmailEntries: Arbitrary[List[CustomerEmailEntry]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[CustomerEmailEntry].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbCustomerPhoneNumberEntries: Arbitrary[List[CustomerPhoneNumberEntry]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[CustomerPhoneNumberEntry].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  private def genEntriesWithSingleDefault[A](genEntry: Gen[A])(setDefault: A => A): Gen[List[A]] =
    Gen.listOf(genEntry).flatMap {
      case Nil     => Gen.const(Nil)
      case entries =>
        Gen
          .choose(0, entries.length - 1)
          .map(defaultIndex => entries.updated(defaultIndex, setDefault(entries(defaultIndex))))
    }

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
