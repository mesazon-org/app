package io.rikkos.gateway.acc

import io.rikkos.domain.*
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.service.UserManagementService
import io.rikkos.gateway.smithy
import io.rikkos.testkit.base.*
import zio.*

class UserManagementServiceSpec extends ZWordSpecBase, DomainArbitraries {

  val userManagementServiceEnv = ZIO.service[smithy.UserManagementService[Task]]

  "UserManagementService" when {
    "onboardUser" should {
      "insert the user successfully" in new TestContext {
        val authMember            = arbitrarySample[AuthMember]
        val userManagementService = buildUserManagementService(authMember)

        userManagementService
          .onboardUser(smithy.OnboardUserDetailsRequest("rikkos", "mappouros", "rikkosLTD"))
          .zioEither
          .isRight shouldBe true
      }

      "fail with BadRequest when request validation fail" in new TestContext {
        val authMember            = arbitrarySample[AuthMember]
        val userManagementService = buildUserManagementService(authMember)

        userManagementService
          .onboardUser(smithy.OnboardUserDetailsRequest("", "mappouros", "rikkosLTD"))
          .zioError
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()
      }

      "fail with InternalServerError when repository fail" in new TestContext {
        val authMember = arbitrarySample[AuthMember]
        val userManagementService = buildUserManagementService(
          authMember = authMember,
          userRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )

        userManagementService
          .onboardUser(smithy.OnboardUserDetailsRequest("rikkos", "mappouros", "rikkosLTD"))
          .zioError
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()
      }
    }
  }

  trait TestContext {
    val userRepositoryRef: Ref[Set[UserDetails]] = Ref.make(Set.empty[UserDetails]).zioValue

    def buildUserManagementService(
        authMember: AuthMember,
        userRepositoryMaybeError: Option[Throwable] = None,
    ): smithy.UserManagementService[Task] =
      userManagementServiceEnv
        .provide(
          UserManagementService.live,
          userRepositoryMockLive(userRepositoryRef, userRepositoryMaybeError),
          authorizationStateMockLive(authMember),
        )
        .zioValue
  }
}
