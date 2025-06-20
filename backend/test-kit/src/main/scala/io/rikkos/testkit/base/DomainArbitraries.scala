package io.rikkos.testkit.base

import io.rikkos.domain.*
import org.scalacheck.*

trait DomainArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[AuthedUser] = Arbitrary(Gen.resultOf(AuthedUser.apply))

  given Arbitrary[OnboardUserDetails] = Arbitrary(Gen.resultOf(OnboardUserDetails.apply))

  given Arbitrary[UserDetails] = Arbitrary(Gen.resultOf(UserDetails.apply))
}
