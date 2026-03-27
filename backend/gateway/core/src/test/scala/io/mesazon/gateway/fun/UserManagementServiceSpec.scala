package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.UserManagementService
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.gateway.validation.*
import io.mesazon.gateway.{smithy, Mocks}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class UserManagementServiceSpec extends ZWordSpecBase, SmithyArbitraries {

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
          userManagementRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .onboardUser(onboardUserDetailsRequest)
          .zioError shouldBe a[smithy.InternalServerError]

        insertUserDetailsCounterRef.get.zioValue shouldBe 1
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
          userManagementRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .updateUser(updateUserDetailsRequest)
          .zioError shouldBe a[smithy.InternalServerError]

        updateUserDetailsCounterRef.get.zioValue shouldBe 1
      }
    }
  }

  trait TestContext {
    val insertUserDetailsCounterRef = Ref.make(0).zioValue
    val updateUserDetailsCounterRef = Ref.make(0).zioValue

    def buildUserManagementService(
        authedUser: AuthedUser,
        userManagementRepositoryMaybeError: Option[Throwable] = None,
    ): smithy.UserManagementService[Task] =
      ZIO
        .service[smithy.UserManagementService[Task]]
        .provide(
          UserManagementService.live,
          Mocks.userManagementRepositoryLive(
            insertUserDetailsCounterRef = insertUserDetailsCounterRef,
            updateUserDetailsCounterRef = updateUserDetailsCounterRef,
            maybeUnexpectedError = userManagementRepositoryMaybeError,
          ),
          Mocks.authorizationStateLive(authedUser),
          Mocks.phoneNumberRegionValidatorLive(),
          UserManagementValidators.onboardUserDetailsRequestValidatorLive,
          UserManagementValidators.updateUserDetailsRequestValidatorLive,
        )
        .zioValue
  }
}
