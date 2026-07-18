package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait CustomerBookDomainArbitraries extends GatewayArbitraries {

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
