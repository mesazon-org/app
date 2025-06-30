package io.rikkos.gateway.utils

import io.github.iltotore.iron.*
import io.rikkos.domain.*
import io.rikkos.gateway.smithy
import io.rikkos.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait GatewayArbitraries extends DomainArbitraries, IronRefinedTypeTransformer {

  given Arbitrary[smithy.OnboardUserDetailsRequest] = Arbitrary {
    for {
      onboardUserDetails  <- Arbitrary.arbitrary[OnboardUserDetails]
      phoneRegion         <- Gen.oneOf(Seq("CY"))
      phoneNationalNumber <- Gen.const("99555555")
      onboardUserDetailsRequest = onboardUserDetails
        .into[smithy.OnboardUserDetailsRequest]
        .withFieldConst(_.phoneRegion, phoneRegion)
        .withFieldConst(_.phoneNationalNumber, phoneNationalNumber)
        .transform
    } yield onboardUserDetailsRequest
  }

  given Arbitrary[smithy.UpdateUserDetailsRequest] =
    Arbitrary(
      summon[Arbitrary[UpdateUserDetails]].arbitrary
        .map(_.transformInto[smithy.UpdateUserDetailsRequest])
    )
}
