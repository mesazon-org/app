package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import io.scalaland.chimney.dsl.*
import org.scalacheck.*

trait UserSignUpSmithyArbitraries extends UserSignUpDomainArbitraries, IronRefinedTypeTransformer {

  given arbSignUpEmailPostRequestSmithy: Arbitrary[smithy.SignUpEmailPostRequest] = Arbitrary(
    Arbitrary.arbitrary[SignUpEmailPostRequest].map(_.transformInto[smithy.SignUpEmailPostRequest])
  )

  given arbSignUpVerifyEmailPostRequestSmithy: Arbitrary[smithy.SignUpVerifyEmailPostRequest] = Arbitrary(
    Arbitrary.arbitrary[SignUpVerifyEmailPostRequest].map(_.transformInto[smithy.SignUpVerifyEmailPostRequest])
  )
}
