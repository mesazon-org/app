package io.rikkos.gateway.it

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import io.github.iltotore.iron.circe.given
import io.rikkos.domain.*
import io.rikkos.testkit.base.IronRefinedTypeArbitraries
import org.scalacheck.*

package object domain extends IronRefinedTypeArbitraries {

  final case class OnboardUserDetailsRequest(
      firstName: FirstName,
      lastName: LastName,
      organization: Organization,
  )

  object OnboardUserDetailsRequest {
    given Arbitrary[OnboardUserDetailsRequest] = Arbitrary(Gen.resultOf(OnboardUserDetailsRequest.apply))

    given Encoder[OnboardUserDetailsRequest] = deriveEncoder[OnboardUserDetailsRequest]
  }

}
