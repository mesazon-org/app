package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.Transformer
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait OrganizationManagementSmithyArbitraries
    extends OrganizationManagementDomainArbitraries,
      IronRefinedTypeTransformer {

  given Transformer[OrganizationPhoneNumber, smithy.PhoneNumberRequest] = organizationPhoneNumber =>
    smithy.PhoneNumberRequest(
      phoneNationalNumber = organizationPhoneNumber.value.phoneNationalNumber.value,
      phoneCountryCode = organizationPhoneNumber.value.phoneCountryCode.value,
    )

  given Transformer[OrganizationEmailEntryRequest, smithy.OrganizationEmailEntryRequest] = entry =>
    smithy.OrganizationEmailEntryRequest(email = entry.email.value, isDefault = entry.isDefault)

  given Transformer[OrganizationPhoneNumberEntryRequest, smithy.OrganizationPhoneNumberEntryRequest] = entry =>
    smithy.OrganizationPhoneNumberEntryRequest(
      phoneNumber = entry.phoneNumber.transformInto[smithy.PhoneNumberRequest],
      isDefault = entry.isDefault,
    )

  given arbCreateOrganizationPostRequestSmithy: Arbitrary[smithy.CreateOrganizationPostRequest] = Arbitrary(
    Arbitrary.arbitrary[CreateOrganizationPostRequest].map(_.transformInto[smithy.CreateOrganizationPostRequest])
  )
}
