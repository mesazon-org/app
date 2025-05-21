package io.rikkos.gateway.unit.utils

import io.rikkos.gateway.smithy
import org.scalacheck.*

trait SmithyArbitraries {

  given Arbitrary[smithy.OnboardUserDetailsRequest] = Arbitrary(Gen.resultOf(smithy.OnboardUserDetailsRequest.apply))
}
