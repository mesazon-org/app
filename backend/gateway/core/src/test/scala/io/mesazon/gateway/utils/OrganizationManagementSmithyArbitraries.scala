package io.mesazon.gateway.utils

import io.github.iltotore.iron.chimney.given
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait OrganizationManagementSmithyArbitraries
    extends OrganizationManagementDomainArbitraries,
      IronRefinedTypeTransformer {

  given arbCreateOrganizationPostRequestSmithy: Arbitrary[smithy.CreateOrganizationPostRequest] = Arbitrary(
    Arbitrary
      .arbitrary[CreateOrganizationPostRequest]
      .map(_.transformInto[smithy.CreateOrganizationPostRequest])
  )
}
