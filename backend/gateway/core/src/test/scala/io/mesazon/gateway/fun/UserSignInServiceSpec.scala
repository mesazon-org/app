package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.{ServiceTask, UserSignInService}
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.*
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class UserSignInServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries {

  "UserSignInService" when {
    "signInEmailPost" should {
      "successfully sign in a user" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID)

        val userSignInService = buildUserSignInServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
        )

        val signInEmailPostResponse = userSignInService.signInPost().zioEither

        assert(signInEmailPostResponse.isRight)

        checkAuthState(
          expectedGetCalls = 1
        )
        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserTokenRepository(
          expectedUpsertUserTokenCalls = 1
        )
        checkUserOtpRepository()
        checkJwtService(
          expectedGenerateAccessTokenCalls = 1,
          expectedGenerateRefreshTokenCalls = 1,
        )
      }

      "fail with UserNotFoundError when user details not found" in new TestContext {
        val authedUser = arbitrarySample[AuthedUser]

        val userSignInService = buildUserSignInServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map.empty,
        )

        val serviceError = userSignInService.signInPost().zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UserNotFoundError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UserNotFoundError] shouldBe ServiceError.InternalServerError
          .UserNotFoundError(
            s"User details not found for userID: [${authedUser.userID}]"
          )

        checkAuthState(
          expectedGetCalls = 1
        )
        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository()
        checkJwtService()
      }

      "fail with UnexpectedError when jwt service fails to generate tokens" in new TestContext {
        val authedUser     = arbitrarySample[AuthedUser]
        val userDetailsRow = arbitrarySample[UserDetailsRow]
          .copy(userID = authedUser.userID)

        val userSignInService = buildUserSignInServiceLive(
          authedUser = authedUser,
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          jwtServiceServiceErrorOpt = Some(ServiceError.InternalServerError.UnexpectedError("JWT generation failed")),
        )

        val serviceError = userSignInService.signInPost().zioError

        serviceError shouldBe a[ServiceError.InternalServerError.UnexpectedError]
        serviceError
          .asInstanceOf[ServiceError.InternalServerError.UnexpectedError]
          .message shouldBe "JWT generation failed"

        checkAuthState(
          expectedGetCalls = 1
        )
        checkUserDetailsRepository(
          expectedGetUserDetailsCalls = 1
        )
        checkUserTokenRepository()
        checkUserOtpRepository()
        checkJwtService(
          expectedGenerateAccessTokenCalls = 1
        )
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserTokenRepositoryMock,
        UserOtpRepositoryMock,
        JwtServiceMock,
        AuthStateMock {

    def buildUserSignInServiceLive(
        authedUser: AuthedUser,
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userTokenRows: Map[TokenID, UserTokenRow] = Map.empty,
        jwtServiceServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userTokenRepositoryServiceErrorOpt: Option[ServiceError] = None,
    ): smithy.UserSignInService[ServiceTask] =
      ZIO
        .service[smithy.UserSignInService[ServiceTask]]
        .provide(
          UserSignInService.local,
          authStateMockLive(authedUser),
          userDetailsRepositoryMockLive(
            userDetailsRows = userDetailsRows,
            serviceErrorOpt = userDetailsRepositoryServiceErrorOpt,
          ),
          userTokenRepositoryMockLive(
            userTokenRows = userTokenRows,
            maybeServiceError = userTokenRepositoryServiceErrorOpt,
          ),
          jwtServiceMockLive(
            maybeServiceError = jwtServiceServiceErrorOpt
          ),
        )
        .zioValue
  }
}
