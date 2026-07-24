package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait CustomerBookDomainArbitraries extends GatewayArbitraries {

  given arbCustomerType: Arbitrary[CustomerType] = Arbitrary(Gen.oneOf(CustomerType.values.toIndexedSeq))

  given arbCustomerStatus: Arbitrary[CustomerStatus] = Arbitrary(Gen.oneOf(CustomerStatus.values.toIndexedSeq))

  given arbCustomerEmailEntryRequest: Arbitrary[CustomerEmailEntryRequest] = Arbitrary(
    Gen.resultOf(CustomerEmailEntryRequest.apply)
  )

  given arbCustomerPhoneNumberEntryRequest: Arbitrary[CustomerPhoneNumberEntryRequest] = Arbitrary(
    Gen.resultOf(CustomerPhoneNumberEntryRequest.apply)
  )

  // A non-empty list must mark exactly one entry as default, so generate non-default entries and promote one at random.
  given arbCustomerEmailEntryRequests: Arbitrary[List[CustomerEmailEntryRequest]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[CustomerEmailEntryRequest].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbCustomerPhoneNumberEntryRequests: Arbitrary[List[CustomerPhoneNumberEntryRequest]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[CustomerPhoneNumberEntryRequest].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbInsertCustomerIndividualPostRequest: Arbitrary[InsertCustomerIndividualPostRequest] = Arbitrary(
    Gen.resultOf(InsertCustomerIndividualPostRequest.apply)
  )

  given arbInsertCustomerIndividualsPostRequest: Arbitrary[InsertCustomerIndividualsPostRequest] = Arbitrary(
    Gen
      .choose(0, 50) // Limit the number of individuals to a reasonable range
      .flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[InsertCustomerIndividualPostRequest]))
      .map(InsertCustomerIndividualsPostRequest.apply)
  )

  given arbUpdateCustomerIndividualPutRequest: Arbitrary[UpdateCustomerIndividualPutRequest] = Arbitrary(
    Gen.resultOf(UpdateCustomerIndividualPutRequest.apply)
  )

  given arbInsertCustomerBusinessContact: Arbitrary[InsertCustomerBusinessContact] = Arbitrary(
    Gen.resultOf(InsertCustomerBusinessContact.apply)
  )

  given arbAddCustomerBusinessContact: Arbitrary[AddCustomerBusinessContact] = Arbitrary(
    Gen.resultOf(AddCustomerBusinessContact.apply)
  )

  given arbAddCustomerBusinessContactsPutRequest: Arbitrary[AddCustomerBusinessContactsPutRequest] = Arbitrary(
    for {
      customerID                 <- Arbitrary.arbitrary[CustomerID]
      addCustomerBusinessContact <- Gen
        .choose(0, 50) // Limit the number of contacts to a reasonable range
        .flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[AddCustomerBusinessContact]))
    } yield AddCustomerBusinessContactsPutRequest(customerID, addCustomerBusinessContact)
  )

  given arbInsertCustomerBusinessPostRequest: Arbitrary[InsertCustomerBusinessPostRequest] = Arbitrary(
    Gen.resultOf(InsertCustomerBusinessPostRequest.apply)
  )

  given arbInsertCustomerBusinessesPostRequest: Arbitrary[InsertCustomerBusinessesPostRequest] = Arbitrary(
    Gen.resultOf(InsertCustomerBusinessesPostRequest.apply)
  )

  given arbUpdateCustomerBusinessPutRequest: Arbitrary[UpdateCustomerBusinessPutRequest] = Arbitrary(
    Gen.resultOf(UpdateCustomerBusinessPutRequest.apply)
  )

  given arbInsertCustomersPostRequest: Arbitrary[InsertCustomersPostRequest] = Arbitrary(
    for {
      insertCustomerBusinessPostRequest <- Gen
        .choose(0, 25) // Limit the number of customers to a reasonable rangee
        .flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[InsertCustomerBusinessPostRequest]))
      insertCustomerIndividualPostRequest <- Gen
        .choose(0, 25) // Limit the number of customers to a reasonable rangee
        .flatMap(n => Gen.listOfN(n, Arbitrary.arbitrary[InsertCustomerIndividualPostRequest]))
    } yield InsertCustomersPostRequest(insertCustomerBusinessPostRequest, insertCustomerIndividualPostRequest)
  )
}
