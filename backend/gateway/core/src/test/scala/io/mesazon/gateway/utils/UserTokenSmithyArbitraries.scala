package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait UserTokenSmithyArbitraries extends UserTokenDomainArbitraries, IronRefinedTypeTransformer {

  given arbTokenRefreshPostRequestSmithy: Arbitrary[smithy.TokenRefreshPostRequest] = Arbitrary(
    Arbitrary.arbitrary[TokenRefreshPostRequest].map(_.transformInto[smithy.TokenRefreshPostRequest])
  )
}
