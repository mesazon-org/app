package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait GatewayArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[OtpType] = Arbitrary(Gen.oneOf(OtpType.values.toIndexedSeq))

  given Arbitrary[OnboardStage] = Arbitrary(Gen.oneOf(OnboardStage.values.toIndexedSeq))

  given Arbitrary[AuthedUser] = Arbitrary(Gen.resultOf(AuthedUser.apply))

  given Arbitrary[OnboardUserDetails] = Arbitrary(Gen.resultOf(OnboardUserDetails.apply))

  given Arbitrary[UpdateUserDetails] = Arbitrary(Gen.resultOf(UpdateUserDetails.apply))

  given Arbitrary[UpsertUserContact] = Arbitrary(Gen.resultOf(UpsertUserContact.apply))

  given Arbitrary[AssistantResponse] = Arbitrary(Gen.resultOf(AssistantResponse.apply))

  given Arbitrary[WahaMessage] = Arbitrary(Gen.resultOf(WahaMessage.apply))
}
