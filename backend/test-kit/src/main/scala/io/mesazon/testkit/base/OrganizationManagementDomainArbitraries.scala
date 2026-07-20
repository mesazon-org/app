package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait OrganizationManagementDomainArbitraries extends GatewayArbitraries {

  given arbOrganizationStage: Arbitrary[OrganizationStage] = Arbitrary(Gen.oneOf(OrganizationStage.values.toIndexedSeq))

  given arbOrganizationUserRole: Arbitrary[OrganizationUserRole] = Arbitrary(
    Gen.oneOf(OrganizationUserRole.values.toIndexedSeq)
  )

  given arbOrganizationEmailEntryRequest: Arbitrary[OrganizationEmailEntryRequest] = Arbitrary(
    Gen.resultOf(OrganizationEmailEntryRequest.apply)
  )

  given arbOrganizationPhoneNumberEntryRequest: Arbitrary[OrganizationPhoneNumberEntryRequest] = Arbitrary(
    Gen.resultOf(OrganizationPhoneNumberEntryRequest.apply)
  )

  // A non-empty list must mark exactly one entry as default, so generate non-default entries and promote one at random.
  given arbOrganizationEmailEntryRequests: Arbitrary[List[OrganizationEmailEntryRequest]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[OrganizationEmailEntryRequest].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbOrganizationPhoneNumberEntryRequests: Arbitrary[List[OrganizationPhoneNumberEntryRequest]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[OrganizationPhoneNumberEntryRequest].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbCreateOrganizationPostRequest: Arbitrary[CreateOrganizationPostRequest] = Arbitrary(
    Gen.resultOf(CreateOrganizationPostRequest.apply)
  )
}
