package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait UserSignInDomainArbitraries extends GatewayArbitraries {

  given arbBasicCredentials: Arbitrary[BasicCredentials] = Arbitrary(Gen.resultOf(BasicCredentials.apply))
}
