package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.UserOnboardRow
import io.mesazon.gateway.service.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.utils.{RepositoryArbitraries, SmithyArbitraries}
import io.mesazon.testkit.base.*
import zio.*

class AuthenticationServiceSpec extends ZWordSpecBase, SmithyArbitraries, RepositoryArbitraries {

  "AuthenticationService" when {
    "signUpEmail" should {
      "successfully sign up a user with valid email" in new TestContext {
        val signUpEmailRequest = arbitrarySample[smithy.SignUpEmailRequest]
        val userOnboardRow     = arbitrarySample[UserOnboardRow]
          .copy(email = Email.assume(signUpEmailRequest.email))
        val authenticationService =
          buildAuthenticationService(
            userOnboardRows = Map(userOnboardRow.userID -> userOnboardRow)
          )

        authenticationService.signUpEmail(signUpEmailRequest).zioValue

        insertUserOnboardEmailRef.get.zioValue shouldBe 1
      }

      "fail with BadRequest when request is invalid" in new TestContext {
        val signUpEmailRequest    = arbitrarySample[smithy.SignUpEmailRequest]
        val authenticationService = buildAuthenticationService(
          emailValidatorMaybeError = Some(
            InvalidFieldError(
              "email",
              "Invalid email",
              "invalidemail",
            )
          )
        )

        authenticationService
          .signUpEmail(signUpEmailRequest)
          .zioEither
          .left
          .value
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()

        insertUserOnboardEmailRef.get.zioValue shouldBe 0
      }

      "fail with InternalServer when fail unexpectedly" in new TestContext {
        val signUpEmailRequest    = arbitrarySample[smithy.SignUpEmailRequest]
        val authenticationService = buildAuthenticationService(
          maybeUserManagementRepositoryError = Some(new RuntimeException("Database connection failed"))
        )

        authenticationService
          .signUpEmail(signUpEmailRequest)
          .zioEither
          .left
          .value
          .asInstanceOf[smithy.InternalServerError] shouldBe smithy.InternalServerError()

        insertUserOnboardEmailRef.get.zioValue shouldBe 0
      }
    }
  }

  trait TestContext {
    val insertUserOnboardEmailRef = Ref.make(0).zioValue

    def buildAuthenticationService(
        userOnboardRows: Map[UserID, UserOnboardRow] = Map.empty,
        maybeUserManagementRepositoryError: Option[Throwable] = None,
        emailValidatorMaybeError: Option[InvalidFieldError] = None,
    ): smithy.AuthenticationService[Task] =
      ZIO
        .service[smithy.AuthenticationService[Task]]
        .provide(
          AuthenticationService.live,
          emailValidatorMockLive(emailValidatorMaybeError),
          userManagementRepositoryMockLive(
            userOnboardRows = userOnboardRows,
            insertUserOnboardEmailRef = insertUserOnboardEmailRef,
            maybeError = maybeUserManagementRepositoryError,
          ),
        )
        .zioValue
  }
}
