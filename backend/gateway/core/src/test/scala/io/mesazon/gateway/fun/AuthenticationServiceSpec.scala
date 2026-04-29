package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.mock.*
import io.mesazon.gateway.repository.domain.*
import io.mesazon.gateway.service.*
import io.mesazon.gateway.utils.RepositoryArbitraries
import io.mesazon.gateway.validation.domain.EmailDomainValidator
import io.mesazon.gateway.validation.service.BasicCredentialsServiceValidator
import io.mesazon.testkit.base.ZWordSpecBase
import org.http4s.headers.Authorization
import org.http4s.{BasicCredentials as Http4sBasicCredentials, *}
import zio.*

class AuthenticationServiceSpec extends ZWordSpecBase, RepositoryArbitraries {

  "AuthenticationService" when {
    "authenticate" should {
      "successfully authenticate user" in new TestContext {
        val password           = arbitrarySample[Password]
        val userDetailsRow     = arbitrarySample[UserDetailsRow]
        val userCredentialsRow = arbitrarySample[UserCredentialsRow]
          .copy(userID = userDetailsRow.userID, passwordHash = PasswordHash.assume(password.value))

        val request = Request[Task](Method.POST, Uri.unsafeFromString("localhost"))
          .withHeaders(
            Authorization(
              Http4sBasicCredentials(userDetailsRow.email.value, password.value)
            )
          )

        val authenticationService = buildAuthenticationService(
          authedUser = AuthedUser(userID = userDetailsRow.userID),
          userDetailsRows = Map(userDetailsRow.userID -> userDetailsRow),
          userCredentialsRows = Map(userCredentialsRow.userID -> userCredentialsRow),
        )

        val authenticationResponse = authenticationService.auth(request).zioEither

        assert(authenticationResponse.isRight)

        checkAuthState(expectedSetCalls = 1)
        checkPasswordService(expectedVerifyPasswordCalls = 1)
        checkUserDetailsRepository(expectedGetUserDetailsByEmailCalls = 1)
        checkUserCredentialsRepository(expectedGetUserCredentialsCalls = 1)
      }
    }
  }

  trait TestContext
      extends UserDetailsRepositoryMock,
        UserCredentialsRepositoryMock,
        PasswordServiceMock,
        AuthStateMock {

    def buildAuthenticationService(
        authedUser: AuthedUser,
        userDetailsRows: Map[UserID, UserDetailsRow] = Map.empty,
        userCredentialsRows: Map[UserID, UserCredentialsRow] = Map.empty,
        passwordServiceErrorOpt: Option[ServiceError] = None,
        userDetailsRepositoryServiceErrorOpt: Option[ServiceError] = None,
        userCredentialsRepositoryServiceErrorOpt: Option[ServiceError] = None,
    ): AuthenticationService[Task] = ZIO
      .service[AuthenticationService[Task]]
      .provide(
        AuthenticationService.live,
        EmailDomainValidator.live,
        BasicCredentialsServiceValidator.live,
        authStateMockLive(authedUser = authedUser),
        passwordServiceMockLive(
          serviceErrorOpt = passwordServiceErrorOpt
        ),
        userDetailsRepositoryMockLive(
          userDetailsRows = userDetailsRows,
          serviceErrorOpt = userDetailsRepositoryServiceErrorOpt,
        ),
        userCredentialsRepositoryMockLive(
          userCredentialsRows = userCredentialsRows,
          serviceErrorOpt = userCredentialsRepositoryServiceErrorOpt,
        ),
      )
      .zioValue
  }

}
