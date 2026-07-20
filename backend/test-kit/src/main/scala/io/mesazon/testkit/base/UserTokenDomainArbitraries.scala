package io.mesazon.testkit.base

import io.mesazon.domain.gateway.*
import org.scalacheck.*

trait UserTokenDomainArbitraries extends GatewayArbitraries {

  given arbTokenRefreshPostRequest: Arbitrary[TokenRefreshPostRequest] = Arbitrary(
    Gen.resultOf(TokenRefreshPostRequest.apply)
  )
}
