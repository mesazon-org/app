package io.mesazon.gateway.unit.state

import io.mesazon.domain.gateway.AuthedUser
import io.mesazon.gateway.state.AuthState
import io.mesazon.testkit.base.{GatewayArbitraries, ZWordSpecBase}
import org.scalactic.anyvals.PosInt
import zio.*

class AuthStateSpec extends ZWordSpecBase, GatewayArbitraries {

  // Overriding the default value of minSuccessful to 1 generated state doesn't affect Authorization results at all.
  override def minSuccessful: PosInt = PosInt(1)

  "AuthState" should {
    "return the state set in the same fiber context" in forAll { (authedUser: AuthedUser) =>
      val stateResult = for {
        authState <- ZIO
          .service[AuthState]
          .provide(
            AuthState.live,
            ZLayer.scoped(FiberRef.make(Option.empty[AuthedUser])),
          )
        _     <- authState.set(authedUser)
        state <- authState.get()
      } yield state

      stateResult.zioValue shouldBe authedUser
    }

    "return the state if set in parent fiber and get is called in child fiber" in forAll { (authedUser: AuthedUser) =>
      val stateResult = for {
        authState <- ZIO
          .service[AuthState]
          .provide(
            AuthState.live,
            ZLayer.scoped(FiberRef.make(Option.empty[AuthedUser])),
          )
        _          <- authState.set(authedUser) // Set the state in a main fiber
        stateFiber <- authState.get().fork      // Get the state in a sub fiber
        state      <- stateFiber.join
      } yield state

      stateResult.zioValue shouldBe authedUser
    }

    "return no state if set in different fiber context" in forAll { (authedUser: AuthedUser) =>
      val stateResult = for {
        authState <- ZIO
          .service[AuthState]
          .provide(
            AuthState.live,
            ZLayer.scoped(FiberRef.make(Option.empty[AuthedUser])),
          )
        _          <- authState.set(authedUser).fork // Set the state in a different fiber
        stateFiber <- authState.get().fork           // Get the state in a different fiber
        state      <- stateFiber.join
      } yield state

      stateResult.cause.zioValue.dieOption.value
        .asInstanceOf[NoSuchElementException]
        .getMessage shouldBe "None.get"
    }
  }
}
