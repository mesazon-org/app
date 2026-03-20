package io.mesazon.gateway.utils

import io.mesazon.gateway.repository.domain.{
  UserContactRow,
  UserDetailsRow,
  WahaUserActivityRow,
  WahaUserMessageRow,
  WahaUserRow,
}
import io.mesazon.testkit.base.IronRefinedTypeArbitraries
import org.scalacheck.{Arbitrary, Gen}

trait RepositoryArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[UserDetailsRow] = Arbitrary(Gen.resultOf(UserDetailsRow.apply))

  given Arbitrary[UserContactRow] = Arbitrary(Gen.resultOf(UserContactRow.apply))

  given Arbitrary[WahaUserRow] = Arbitrary(Gen.resultOf(WahaUserRow.apply))

  given Arbitrary[WahaUserActivityRow] = Arbitrary(Gen.resultOf(WahaUserActivityRow.apply))

  given Arbitrary[WahaUserMessageRow] = Arbitrary(Gen.resultOf(WahaUserMessageRow.apply))

}
