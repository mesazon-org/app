package io.rikkos.gateway.fun

import io.rikkos.domain.*
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.service.UserManagementService
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.gateway.validation.*
import io.rikkos.testkit.base.*
import zio.*

class UserManagementServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  "UserManagementService" when {
    "onboardUser" should {
      "successfully insert the user" in new TestContext {
        val authedUser                = arbitrarySample[AuthedUser]
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
        val userManagementService     = buildUserManagementService(authedUser)

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioEither
          .isRight shouldBe true

        insertUserDetailsCounterRef.get.zioValue shouldBe 1
      }

      "fail with BadRequest when request validation fail" in new TestContext {
        val authedUser                = arbitrarySample[AuthedUser]
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
          .copy(firstName = "")
        val userManagementService = buildUserManagementService(authedUser)

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioError shouldBe a[smithy.BadRequest]

        insertUserDetailsCounterRef.get.zioValue shouldBe 0
      }

      "fail with InternalServerError when repository fail" in new TestContext {
        val authedUser                = arbitrarySample[AuthedUser]
        val onboardUserDetailsRequest = arbitrarySample[smithy.OnboardUserDetailsRequest]
        val userManagementService     = buildUserManagementService(
          authedUser = authedUser,
          userRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioError shouldBe a[smithy.InternalServerError]

        insertUserDetailsCounterRef.get.zioValue shouldBe 0
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

        updateUserDetailsCounterRef.get.zioValue shouldBe 1
      }

      "fail with BadRequest when request validation fail" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
          .copy(firstName = Some(""))
        val userManagementService = buildUserManagementService(authedUser)

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError shouldBe a[smithy.BadRequest]

        updateUserDetailsCounterRef.get.zioValue shouldBe 0
      }

      "fail with BadRequest when request contains no updates" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = smithy.UpdateUserDetailsRequest()
        val userManagementService    = buildUserManagementService(authedUser)

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError shouldBe a[smithy.BadRequest]

        updateUserDetailsCounterRef.get.zioValue shouldBe 0
      }

      "fail with InternalServerError when repository fail" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val updateUserDetailsRequest = arbitrarySample[smithy.UpdateUserDetailsRequest]
        val userManagementService    = buildUserManagementService(
          authedUser = authedUser,
          userRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError shouldBe a[smithy.InternalServerError]

        updateUserDetailsCounterRef.get.zioValue shouldBe 0
      }
    }
  }

  trait TestContext {
    val insertUserDetailsCounterRef = Ref.make(0).zioValue
    val updateUserDetailsCounterRef = Ref.make(0).zioValue

    def buildUserManagementService(
        authedUser: AuthedUser,
        userRepositoryMaybeError: Option[Throwable] = None,
    ): smithy.UserManagementService[Task] =
      ZIO
        .service[smithy.UserManagementService[Task]]
        .provide(
          UserManagementService.live,
          userRepositoryMockLive(
            insertUserDetailsCounterRef,
            updateUserDetailsCounterRef,
            userRepositoryMaybeError,
          ),
          authorizationStateMockLive(authedUser),
          phoneNumberValidatorMockLive(),
          UserManagementValidators.onboardUserDetailsRequestValidatorLive,
          UserManagementValidators.updateUserDetailsRequestValidatorLive,
        )
        .zioValue
  }
}
