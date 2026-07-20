package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait OrganizationManagementDomainArbitraries extends GatewayArbitraries {

  given arbOrganizationStage: Arbitrary[OrganizationStage] = Arbitrary(Gen.oneOf(OrganizationStage.values.toIndexedSeq))

  given arbOrganizationUserRole: Arbitrary[OrganizationUserRole] = Arbitrary(
    Gen.oneOf(OrganizationUserRole.values.toIndexedSeq)
  )

  given arbOrganizationEmailEntry: Arbitrary[OrganizationEmailEntry] = Arbitrary(
    Gen.resultOf(OrganizationEmailEntry.apply)
  )

  given arbOrganizationPhoneNumberEntry: Arbitrary[OrganizationPhoneNumberEntry] = Arbitrary(
    Gen.resultOf(OrganizationPhoneNumberEntry.apply)
  )

  // A non-empty list must mark exactly one entry as default, so generate non-default entries and promote one at random.
  given arbOrganizationEmailEntries: Arbitrary[List[OrganizationEmailEntry]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[OrganizationEmailEntry].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbOrganizationPhoneNumberEntries: Arbitrary[List[OrganizationPhoneNumberEntry]] = Arbitrary(
    genEntriesWithSingleDefault(
      Arbitrary.arbitrary[OrganizationPhoneNumberEntry].map(_.copy(isDefault = false))
    )(_.copy(isDefault = true))
  )

  given arbCreateOrganization: Arbitrary[CreateOrganization] = Arbitrary(Gen.resultOf(CreateOrganization.apply))
}
