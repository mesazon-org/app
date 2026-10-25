package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

import java.util.UUID

trait CustomerBookDomainArbitraries extends GatewayArbitraries {

  private inline val CustomerBusinessContactCountMinimum = 0
  private inline val CustomerBusinessContactCountMaximum = 50
  private inline val CustomerBookBatchEntryCountMinimum  = 0
  private inline val CustomerBookBatchEntryCountMaximum  = 25

  given arbCustomerType: Arbitrary[CustomerType] = Arbitrary(Gen.oneOf(CustomerType.values.toIndexedSeq))

  given arbCustomerAddressEntries: Arbitrary[List[CustomerAddressEntry]] = Arbitrary(
    genAddressEntriesAllowedCombination.map(_.map(CustomerAddressEntry(_)))
  )

  given arbCustomerEmailEntry: Arbitrary[CustomerEmailEntry] = Arbitrary(
    Gen.resultOf(CustomerEmailEntry.apply)
  )

  given arbCustomerPhoneNumberEntry: Arbitrary[CustomerPhoneNumberEntry] = Arbitrary(
    Gen.resultOf(CustomerPhoneNumberEntry.apply)
  )

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

  given arbCustomerBusinessContactInsert: Arbitrary[CustomerBusinessContactInsert] = Arbitrary(
    Gen.resultOf(CustomerBusinessContactInsert.apply)
  )

  given arbCustomerBusinessContactsInsert: Arbitrary[List[CustomerBusinessContactInsert]] = Arbitrary(
    Gen
      .choose(CustomerBusinessContactCountMinimum, CustomerBusinessContactCountMaximum)
      .flatMap(count => Gen.listOfN(count, Arbitrary.arbitrary[CustomerBusinessContactInsert]))
  )

  given arbCustomerBusinessContactUpdate: Arbitrary[CustomerBusinessContactUpdate] = Arbitrary(
    Gen.resultOf(CustomerBusinessContactUpdate.apply)
  )

  given arbCustomerBusinessContactsUpdate: Arbitrary[List[CustomerBusinessContactUpdate]] = Arbitrary(
    Gen
      .choose(CustomerBusinessContactCountMinimum, CustomerBusinessContactCountMaximum)
      .flatMap(count => Gen.listOfN(count, Arbitrary.arbitrary[CustomerBusinessContactUpdate]))
  )

  given arbCustomerBusinessContactArchive: Arbitrary[CustomerBusinessContactArchive] = Arbitrary(
    Gen.resultOf(CustomerBusinessContactArchive.apply)
  )

  given arbCustomerBusinessContactsArchive: Arbitrary[List[CustomerBusinessContactArchive]] = Arbitrary(
    Gen
      .choose(CustomerBusinessContactCountMinimum, CustomerBusinessContactCountMaximum)
      .flatMap(count => Gen.listOfN(count, Arbitrary.arbitrary[CustomerBusinessContactArchive]))
  )

  given arbCustomerBookIndividualPostRequest: Arbitrary[CustomerBookIndividualPostRequest] = Arbitrary(
    Gen.resultOf(CustomerBookIndividualPostRequest.apply)
  )

  given arbCustomerBookBusinessPostRequest: Arbitrary[CustomerBookBusinessPostRequest] = Arbitrary(
    Gen.resultOf(CustomerBookBusinessPostRequest.apply)
  )

  given arbCustomerBookIndividualPutRequest: Arbitrary[CustomerBookIndividualPutRequest] = Arbitrary(
    Gen.resultOf(CustomerBookIndividualPutRequest.apply)
  )

  given arbCustomerBookBusinessPutRequest: Arbitrary[CustomerBookBusinessPutRequest] = Arbitrary(
    for {
      customerBookBusinessPutRequest <- Gen.resultOf(CustomerBookBusinessPutRequest.apply)
      customerBusinessContactIDBase  <- Arbitrary.arbitrary[CustomerBusinessContactID]
    } yield customerBookBusinessPutRequest.copy(
      customerBusinessContactsUpdate = customerBookBusinessPutRequest.customerBusinessContactsUpdate.zipWithIndex.map {
        case (customerBusinessContactUpdate, index) =>
          customerBusinessContactUpdate.copy(
            customerBusinessContactID = customerBusinessContactIDAtIndex(customerBusinessContactIDBase, index)
          )
      },
      customerBusinessContactsArchive =
        customerBookBusinessPutRequest.customerBusinessContactsArchive.zipWithIndex.map {
          case (customerBusinessContactArchive, index) =>
            customerBusinessContactArchive.copy(
              customerBusinessContactID = customerBusinessContactIDAtIndex(
                customerBusinessContactIDBase,
                customerBookBusinessPutRequest.customerBusinessContactsUpdate.size + index,
              )
            )
        },
    )
  )

  given arbCustomerBookBatchPostRequest: Arbitrary[CustomerBookBatchPostRequest] = Arbitrary(
    for {
      customerBusinesses <- Gen
        .choose(CustomerBookBatchEntryCountMinimum, CustomerBookBatchEntryCountMaximum)
        .flatMap(count => Gen.listOfN(count, Arbitrary.arbitrary[CustomerBookBusinessPostRequest]))
      customerIndividuals <- Gen
        .choose(CustomerBookBatchEntryCountMinimum, CustomerBookBatchEntryCountMaximum)
        .flatMap(count => Gen.listOfN(count, Arbitrary.arbitrary[CustomerBookIndividualPostRequest]))
    } yield CustomerBookBatchPostRequest(customerBusinesses, customerIndividuals)
  )

  given arbCustomerBookUniquenessGetRequest: Arbitrary[CustomerBookUniquenessGetRequest] = Arbitrary(
    Gen.resultOf(CustomerBookUniquenessGetRequest.apply)
  )

  private def customerBusinessContactIDAtIndex(
      customerBusinessContactIDBase: CustomerBusinessContactID,
      index: Int,
  ): CustomerBusinessContactID =
    CustomerBusinessContactID(
      new UUID(
        customerBusinessContactIDBase.value.getMostSignificantBits,
        customerBusinessContactIDBase.value.getLeastSignificantBits + index.toLong,
      )
    )

}
