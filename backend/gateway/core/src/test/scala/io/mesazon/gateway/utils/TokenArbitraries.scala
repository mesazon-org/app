package io.mesazon.gateway.utils

import io.mesazon.gateway.service.JwtService.*
import io.mesazon.testkit.base.IronRefinedTypeArbitraries
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait TokenArbitraries extends IronRefinedTypeArbitraries {

  given Arbitrary[AccessJwt] = Arbitrary(Gen.resultOf(AccessJwt.apply))
  given Arbitrary[ResetPasswordJwt] = Arbitrary(Gen.resultOf(ResetPasswordJwt.apply))
  given Arbitrary[RefreshJwt] = Arbitrary(Gen.resultOf(RefreshJwt.apply))

  given Arbitrary[AuthedUserRefresh] = Arbitrary(Gen.resultOf(AuthedUserRefresh.apply))
  given Arbitrary[AuthedUserResetPassword] = Arbitrary(Gen.resultOf(AuthedUserResetPassword.apply))
  given Arbitrary[AuthedUserAccess] = Arbitrary(Gen.resultOf(AuthedUserAccess.apply))

}
