package io.rikkos.gateway.utils

import io.rikkos.gateway.repository.domain.*
import io.rikkos.testkit.base.{GatewayArbitraries, IronRefinedTypeTransformer}
import org.scalacheck.{Arbitrary, Gen}

trait RepositoryArbitraries extends GatewayArbitraries, IronRefinedTypeTransformer {

  given Arbitrary[UserDetailsRow] = Arbitrary(Gen.resultOf(UserDetailsRow.apply))

  given Arbitrary[UserContactRow] = Arbitrary(Gen.resultOf(UserContactRow.apply))

  given Arbitrary[WahaUserRow] = Arbitrary(Gen.resultOf(WahaUserRow.apply))

  given Arbitrary[WahaUserActivityRow] = Arbitrary(Gen.resultOf(WahaUserActivityRow.apply))

  given Arbitrary[WahaUserMessageRow] = Arbitrary(Gen.resultOf(WahaUserMessageRow.apply))

}
