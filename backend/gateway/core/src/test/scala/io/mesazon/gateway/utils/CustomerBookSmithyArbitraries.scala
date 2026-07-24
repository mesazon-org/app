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

  given Transformer[CustomerEmailEntryRequest, smithy.CustomerEmailEntryRequest] = entry =>
    smithy.CustomerEmailEntryRequest(email = entry.email.value, isDefault = entry.isDefault)

  given Transformer[CustomerPhoneNumberEntryRequest, smithy.CustomerPhoneNumberEntryRequest] = entry =>
    smithy.CustomerPhoneNumberEntryRequest(
      phoneNumber = entry.phoneNumber.transformInto[smithy.PhoneNumberRequest],
      isDefault = entry.isDefault,
    )

  given Transformer[InsertCustomerBusinessPostRequest, smithy.InsertCustomerBusinessPostRequest] = business =>
    business
      .into[smithy.InsertCustomerBusinessPostRequest]
      .withFieldComputed(
        _.customerBusinessContacts,
        business => business.customerBusinessContacts.map(_.transformInto[smithy.InsertCustomerBusinessContact]),
      )
      .transform

  given arbInsertCustomerIndividualPostRequestSmithy: Arbitrary[smithy.InsertCustomerIndividualPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[InsertCustomerIndividualPostRequest]
      .map(_.transformInto[smithy.InsertCustomerIndividualPostRequest])
  )

  given arbInsertCustomerIndividualsPostRequestSmithy: Arbitrary[smithy.InsertCustomerIndividualsPostRequest] =
    Arbitrary(
      Arbitrary
        .arbitrary[InsertCustomerIndividualsPostRequest]
        .map(_.transformInto[smithy.InsertCustomerIndividualsPostRequest])
    )

  given arbInsertCustomerBusinessPostRequestSmithy: Arbitrary[smithy.InsertCustomerBusinessPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[InsertCustomerBusinessPostRequest]
      .map(_.transformInto[smithy.InsertCustomerBusinessPostRequest])
  )

  given arbInsertCustomerBusinessesPostRequestSmithy: Arbitrary[smithy.InsertCustomerBusinessesPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[InsertCustomerBusinessesPostRequest]
      .map(_.transformInto[smithy.InsertCustomerBusinessesPostRequest])
  )

  given arbInsertCustomersPostRequestSmithy: Arbitrary[smithy.InsertCustomersPostRequest] = Arbitrary(
    Arbitrary.arbitrary[InsertCustomersPostRequest].map(_.transformInto[smithy.InsertCustomersPostRequest])
  )

  given arbUpdateCustomerIndividualPutRequestSmithy: Arbitrary[smithy.UpdateCustomerIndividualPutRequest] = Arbitrary(
    Arbitrary
      .arbitrary[UpdateCustomerIndividualPutRequest]
      .map(_.transformInto[smithy.UpdateCustomerIndividualPutRequest])
  )

  given arbUpdateCustomerBusinessPutRequestSmithy: Arbitrary[smithy.UpdateCustomerBusinessPutRequest] = Arbitrary(
    Arbitrary.arbitrary[UpdateCustomerBusinessPutRequest].map(_.transformInto[smithy.UpdateCustomerBusinessPutRequest])
  )

  given arbAddCustomerBusinessContactSmithy: Arbitrary[smithy.AddCustomerBusinessContact] = Arbitrary(
    Arbitrary.arbitrary[AddCustomerBusinessContact].map(_.transformInto[smithy.AddCustomerBusinessContact])
  )

  given arbAddCustomerBusinessContactsPutRequestSmithy: Arbitrary[smithy.AddCustomerBusinessContactsPutRequest] =
    Arbitrary(
      Arbitrary
        .arbitrary[AddCustomerBusinessContactsPutRequest]
        .map(_.transformInto[smithy.AddCustomerBusinessContactsPutRequest])
    )

  given arbRemoveCustomerBusinessContactsPutRequestSmithy: Arbitrary[smithy.RemoveCustomerBusinessContactsPutRequest] =
    Arbitrary(
      for {
        customerID               <- Gen.uuid
        customerBusinessContacts <- Gen.listOf(Gen.uuid.map(smithy.RemoveCustomerBusinessContact.apply))
      } yield smithy.RemoveCustomerBusinessContactsPutRequest(customerID, customerBusinessContacts)
    )
}
