package io.mesazon.gateway.utils

import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.*
import org.scalacheck.{Arbitrary, Gen}

trait RepositoryArbitraries extends GatewayArbitraries, IronRefinedTypeArbitraries {

  given Arbitrary[UserDetailsRow] = Arbitrary(Gen.resultOf(UserDetailsRow.apply))

  given Arbitrary[UserContactRow] = Arbitrary(Gen.resultOf(UserContactRow.apply))

  given Arbitrary[WahaUserRow] = Arbitrary(Gen.resultOf(WahaUserRow.apply))

  given Arbitrary[WahaUserActivityRow] = Arbitrary(Gen.resultOf(WahaUserActivityRow.apply))

  given Arbitrary[WahaUserMessageRow] = Arbitrary(Gen.resultOf(WahaUserMessageRow.apply))

  given Arbitrary[UserOnboardRow] = Arbitrary(Gen.resultOf(UserOnboardRow.apply))
}
