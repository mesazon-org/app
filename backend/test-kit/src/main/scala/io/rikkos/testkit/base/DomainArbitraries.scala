package io.rikkos.testkit.base

import io.rikkos.domain.*
import org.scalacheck.*

trait DomainArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[AuthMember] = Arbitrary(Gen.resultOf(AuthMember.apply))

  given Arbitrary[OnboardUserDetails] = Arbitrary(Gen.resultOf(OnboardUserDetails.apply))
}
