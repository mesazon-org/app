package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.given
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait CustomerBookSmithyArbitraries extends CustomerBookDomainArbitraries, IronRefinedTypeTransformer {

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

  given arbArchiveCustomerPutRequestSmithy: Arbitrary[smithy.ArchiveCustomerPutRequest] =
    Arbitrary(Gen.uuid.map(smithy.ArchiveCustomerPutRequest.apply))
}
