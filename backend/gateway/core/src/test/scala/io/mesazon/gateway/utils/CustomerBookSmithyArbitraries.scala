package io.mesazon.gateway.utils

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.transform.given
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait CustomerBookSmithyArbitraries extends CustomerBookDomainArbitraries, IronRefinedTypeTransformer {

  given arbCustomerBookIndividualPostRequestSmithy: Arbitrary[smithy.CustomerBookIndividualPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[CustomerBookIndividualPostRequest]
      .map(_.transformInto[smithy.CustomerBookIndividualPostRequest])
  )

  given arbCustomerBookBusinessPostRequestSmithy: Arbitrary[smithy.CustomerBookBusinessPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CustomerBookBusinessPostRequest].map(_.transformInto[smithy.CustomerBookBusinessPostRequest])
  )

  given arbCustomerBookBatchPostRequestSmithy: Arbitrary[smithy.CustomerBookBatchPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CustomerBookBatchPostRequest].map(_.transformInto[smithy.CustomerBookBatchPostRequest])
  )

  given arbCustomerBookIndividualPutRequestSmithy: Arbitrary[smithy.CustomerBookIndividualPutRequest] = Arbitrary(
    Arbitrary.arbitrary[CustomerBookIndividualPutRequest].map(_.transformInto[smithy.CustomerBookIndividualPutRequest])
  )

  given arbCustomerBusinessContactUpdateSmithy: Arbitrary[smithy.CustomerBusinessContactUpdate] = Arbitrary(
    Arbitrary.arbitrary[CustomerBusinessContactUpdate].map(_.transformInto[smithy.CustomerBusinessContactUpdate])
  )

  given arbCustomerBusinessContactArchiveSmithy: Arbitrary[smithy.CustomerBusinessContactArchive] = Arbitrary(
    Arbitrary.arbitrary[CustomerBusinessContactArchive].map(_.transformInto[smithy.CustomerBusinessContactArchive])
  )

  given arbCustomerBookBusinessPutRequestSmithy: Arbitrary[smithy.CustomerBookBusinessPutRequest] = Arbitrary(
    Arbitrary.arbitrary[CustomerBookBusinessPutRequest].map(_.transformInto[smithy.CustomerBookBusinessPutRequest])
  )

  given arbCustomerBusinessContactsUpdateSmithy: Arbitrary[List[smithy.CustomerBusinessContactUpdate]] = Arbitrary(
    Arbitrary
      .arbitrary[List[CustomerBusinessContactUpdate]]
      .map(_.transformInto[List[smithy.CustomerBusinessContactUpdate]])
  )

  given arbCustomerBusinessContactsArchiveSmithy: Arbitrary[List[smithy.CustomerBusinessContactArchive]] = Arbitrary(
    Arbitrary
      .arbitrary[List[CustomerBusinessContactArchive]]
      .map(_.transformInto[List[smithy.CustomerBusinessContactArchive]])
  )

  given arbCustomerBookUniquenessGetInputSmithy: Arbitrary[smithy.CustomerBookUniquenessGetInput] = Arbitrary(
    for {
      organizationID                   <- Arbitrary.arbitrary[OrganizationID]
      customerBookUniquenessGetRequest <- Arbitrary.arbitrary[CustomerBookUniquenessGetRequest]
    } yield customerBookUniquenessGetRequest
      .into[smithy.CustomerBookUniquenessGetInput]
      .withFieldConst(_.organizationID, organizationID.value)
      .transform
  )

}
