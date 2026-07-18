package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait CustomerBookSmithyArbitraries extends CustomerBookDomainArbitraries, IronRefinedTypeTransformer {

  given Transformer[CustomerPhoneNumber, smithy.PhoneNumberRequest] = customerPhoneNumber =>
    smithy.PhoneNumberRequest(
      phoneNationalNumber = customerPhoneNumber.value.phoneNationalNumber.value,
      phoneCountryCode = customerPhoneNumber.value.phoneCountryCode.value,
    )

  given Transformer[InsertCustomerBusiness, smithy.InsertCustomerBusinessPostRequest] = business =>
    business
      .into[smithy.InsertCustomerBusinessPostRequest]
      .withFieldComputed(
        _.customerBusinessContacts,
        business => Some(business.customerBusinessContacts.map(_.transformInto[smithy.InsertCustomerBusinessContact])),
      )
      .transform

  given arbInsertCustomerIndividualPostRequest: Arbitrary[smithy.InsertCustomerIndividualPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCustomerIndividual].map(_.transformInto[smithy.InsertCustomerIndividualPostRequest])
  )

  given arbInsertCustomerIndividualsPostRequest: Arbitrary[smithy.InsertCustomerIndividualsPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCustomerIndividuals].map(_.transformInto[smithy.InsertCustomerIndividualsPostRequest])
  )

  given arbInsertCustomerBusinessPostRequest: Arbitrary[smithy.InsertCustomerBusinessPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCustomerBusiness].map(_.transformInto[smithy.InsertCustomerBusinessPostRequest])
  )

  given arbInsertCustomerBusinessesPostRequest: Arbitrary[smithy.InsertCustomerBusinessesPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCustomerBusinesses].map(_.transformInto[smithy.InsertCustomerBusinessesPostRequest])
  )

  given arbInsertCustomersPostRequest: Arbitrary[smithy.InsertCustomersPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCustomers].map(_.transformInto[smithy.InsertCustomersPostRequest])
  )

  given arbUpdateCustomerIndividualPutRequest: Arbitrary[smithy.UpdateCustomerIndividualPutRequest] = Arbitrary(
    Arbitrary.arbitrary[UpdateCustomerIndividual].map(_.transformInto[smithy.UpdateCustomerIndividualPutRequest])
  )

  given arbUpdateCustomerBusinessPutRequest: Arbitrary[smithy.UpdateCustomerBusinessPutRequest] = Arbitrary(
    Arbitrary.arbitrary[UpdateCustomerBusiness].map(_.transformInto[smithy.UpdateCustomerBusinessPutRequest])
  )

  given arbAddCustomerBusinessContactSmithy: Arbitrary[smithy.AddCustomerBusinessContact] = Arbitrary(
    Arbitrary.arbitrary[AddCustomerBusinessContact].map(_.transformInto[smithy.AddCustomerBusinessContact])
  )

  given arbAddCustomerBusinessContactsPutRequest: Arbitrary[smithy.AddCustomerBusinessContactsPutRequest] = Arbitrary(
    Arbitrary.arbitrary[AddCustomerBusinessContacts].map(_.transformInto[smithy.AddCustomerBusinessContactsPutRequest])
  )
}
