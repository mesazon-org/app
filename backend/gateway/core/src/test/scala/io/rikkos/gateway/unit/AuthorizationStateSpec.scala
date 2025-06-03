package io.rikkos.gateway.unit

import io.rikkos.domain.AuthedUser
import io.rikkos.gateway.auth.AuthorizationState
import io.rikkos.testkit.base.{DomainArbitraries, ZWordSpecBase}
import org.scalactic.anyvals.PosInt
import zio.*

class AuthorizationStateSpec extends ZWordSpecBase, DomainArbitraries {

  // Overriding the default value of minSuccessful to 1 generated state doesn't affect Authorization results at all.
  override def minSuccessful: PosInt = PosInt(1)

  "AuthorizationState" should {
    "return the state set in the same fiber context" in forAll { (authedUser: AuthedUser) =>
      val stateResult = for {
        authorizationState <- ZIO
          .service[AuthorizationState]
          .provide(AuthorizationState.live)
        _     <- authorizationState.set(authedUser)
        state <- authorizationState.get()
      } yield state

      stateResult.zioValue shouldBe authedUser
    }

    "return the state if set in parent fiber and get is called in child fiber" in forAll { (authedUser: AuthedUser) =>
      val stateResult = for {
        authorizationState <- ZIO
          .service[AuthorizationState]
          .provide(AuthorizationState.live)
        _          <- authorizationState.set(authedUser) // Set the state in a main fiber
        stateFiber <- authorizationState.get().fork      // Get the state in a sub fiber
        state      <- stateFiber.join
      } yield state

      stateResult.zioValue shouldBe authedUser
    }

    "return no state if set in different fiber context" in forAll { (authedUser: AuthedUser) =>
      val stateResult = for {
        authorizationState <- ZIO
          .service[AuthorizationState]
          .provide(AuthorizationState.live)
        _          <- authorizationState.set(authedUser).fork // Set the state in a different fiber
        stateFiber <- authorizationState.get().fork           // Get the state in a different fiber
        state      <- stateFiber.join
      } yield state

      stateResult.cause.zioValue.dieOption.value
        .asInstanceOf[NoSuchElementException]
        .getMessage shouldBe "None.get"
    }
  }
}
