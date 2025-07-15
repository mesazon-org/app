package io.rikkos.testkit.base

import io.rikkos.domain.*
import org.scalacheck.*

trait DomainArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[AuthedUser] = Arbitrary(Gen.resultOf(AuthedUser.apply))

  given Arbitrary[OnboardUserDetails] = Arbitrary(Gen.resultOf(OnboardUserDetails.apply))

  given Arbitrary[UpdateUserDetails] = Arbitrary(Gen.resultOf(UpdateUserDetails.apply))

  given Arbitrary[UserDetailsTable] = Arbitrary(Gen.resultOf(UserDetailsTable.apply))

  given Arbitrary[UpsertUserContact] = Arbitrary(Gen.resultOf(UpsertUserContact.apply))
}
