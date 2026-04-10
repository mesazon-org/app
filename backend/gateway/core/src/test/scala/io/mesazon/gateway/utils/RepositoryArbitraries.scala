package io.mesazon.gateway.utils

import io.mesazon.gateway.repository.domain.*
import io.mesazon.testkit.base.*
import org.scalacheck.*

trait RepositoryArbitraries extends GatewayArbitraries, IronRefinedTypeArbitraries {

  given Arbitrary[UserDetailsRow] = Arbitrary(Gen.resultOf(UserDetailsRow.apply))

  given Arbitrary[WahaUserRow] = Arbitrary(Gen.resultOf(WahaUserRow.apply))

  given Arbitrary[WahaUserActivityRow] = Arbitrary(Gen.resultOf(WahaUserActivityRow.apply))

  given Arbitrary[WahaUserMessageRow] = Arbitrary(Gen.resultOf(WahaUserMessageRow.apply))

  given Arbitrary[UserOtpRow] = Arbitrary(Gen.resultOf(UserOtpRow.apply))

  given Arbitrary[UserTokenRow] = Arbitrary(Gen.resultOf(UserTokenRow.apply))

  given Arbitrary[UserCredentialsRow] = Arbitrary(Gen.resultOf(UserCredentialsRow.apply))
}
