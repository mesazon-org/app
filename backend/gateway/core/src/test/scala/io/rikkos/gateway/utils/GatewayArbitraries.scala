package io.rikkos.gateway.utils

import io.rikkos.domain.{OnboardUserDetails, UpdateUserDetails}
import io.rikkos.gateway.smithy
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait GatewayArbitraries extends DomainArbitraries, IronRefinedTypeTransformer {

  given Arbitrary[smithy.OnboardUserDetailsRequest] =
    Arbitrary(
      summon[Arbitrary[OnboardUserDetails]].arbitrary
        .map(_.transformInto[smithy.OnboardUserDetailsRequest])
    )

  given Arbitrary[smithy.UpdateUserDetailsRequest] =
    Arbitrary(
      summon[Arbitrary[UpdateUserDetails]].arbitrary
        .map(_.transformInto[smithy.UpdateUserDetailsRequest])
    )
}
