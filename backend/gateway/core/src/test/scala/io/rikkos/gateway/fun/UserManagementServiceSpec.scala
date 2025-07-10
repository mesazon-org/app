package io.rikkos.gateway.fun

import io.rikkos.domain.*
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.service.UserManagementService
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.gateway.validation.ServiceValidator
import io.rikkos.testkit.base.*
import zio.*

class UserManagementServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  val userManagementServiceEnv = ZIO.service[smithy.UserManagementService[Task]]

  "UserManagementService" when {
    "onboardUser" should {
      "insert the user successfully" in new TestContext {
        val authedUser                = arbitrarySample[AuthedUser]
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
        val userManagementService     = buildUserManagementService(authedUser)

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioEither
          .isRight shouldBe true
      }

      "fail with BadRequest when request validation fail" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
          .copy(firstName = "")
        val userManagementService = buildUserManagementService(authedUser)

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioError
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()
      }

      "fail with InternalServerError when repository fail" in new TestContext {
        val authedUser                = arbitrarySample[AuthedUser]
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
        val userManagementService = buildUserManagementService(
          authedUser = authedUser,
          userRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()
      }
    }

    "updateUser" should {
      "update the user successfully" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
        val userManagementService    = buildUserManagementService(authedUser)

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioEither
          .isRight shouldBe true
      }

      "fail with BadRequest when request validation fail" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
          .copy(firstName = Some(""))
        val userManagementService = buildUserManagementService(authedUser)

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()
      }

      "fail with BadRequest when request contains no updates" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = smithy.UpdateUserDetailsRequest()
        val userManagementService    = buildUserManagementService(authedUser)

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()
      }

      "fail with InternalServerError when repository fail" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
        val userManagementService = buildUserManagementService(
          authedUser = authedUser,
          userRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()
      }
    }
  }

  trait TestContext {
    val userRepositoryRef: Ref[Set[OnboardUserDetails]] = Ref.make(Set.empty[OnboardUserDetails]).zioValue

    def buildUserManagementService(
        authedUser: AuthedUser,
        userRepositoryMaybeError: Option[Throwable] = None,
    ): smithy.UserManagementService[Task] =
      userManagementServiceEnv
        .provide(
          UserManagementService.live,
          userRepositoryMockLive(userRepositoryRef, userRepositoryMaybeError),
          authorizationStateMockLive(authedUser),
          phoneNumberValidatorMockLive(),
          ServiceValidator.onboardUserDetailsRequestValidatorLive,
          ServiceValidator.updateUserDetailsRequestValidatorLive,
        )
        .zioValue
  }
}
