package io.rikkos.testkit.base

import io.rikkos.domain.gateway.*
import org.scalacheck.*

trait GatewayArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[AuthedUser] = Arbitrary(Gen.resultOf(AuthedUser.apply))

  given Arbitrary[OnboardUserDetails] = Arbitrary(Gen.resultOf(OnboardUserDetails.apply))

  given Arbitrary[UpdateUserDetails] = Arbitrary(Gen.resultOf(UpdateUserDetails.apply))

  given Arbitrary[UpsertUserContact] = Arbitrary(Gen.resultOf(UpsertUserContact.apply))
}
