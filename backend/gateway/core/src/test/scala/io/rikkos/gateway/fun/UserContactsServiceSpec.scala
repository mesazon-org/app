package io.rikkos.gateway.fun

import io.rikkos.domain.*
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.service.*
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.GatewayArbitraries
import io.rikkos.gateway.validation.*
import io.rikkos.testkit.base.ZWordSpecBase
import zio.*

class UserContactsServiceSpec extends ZWordSpecBase, GatewayArbitraries {

  "UserContactService" when {
    "upsertContacts" should {
      "successfully upsert user contacts" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val upsertUserContactRequest = arbitrarySample[smithy.UpsertUserContactRequest](5).toList.toSet

        buildUserContactsService(authedUser)
          .upsertContacts(upsertUserContactRequest)
          .zioEither
          .isRight shouldBe true

        upsertUserContactsCounterRef.get.zioValue shouldBe 1
      }

      "fail with BadRequest when request validation fail" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val upsertUserContactRequest = arbitrarySample[smithy.UpsertUserContactRequest]
          .copy(displayName = "")

        buildUserContactsService(authedUser)
          .upsertContacts(Set(upsertUserContactRequest))
          .zioError
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest()

        upsertUserContactsCounterRef.get.zioValue shouldBe 0
      }

      "fail with InternalServerError when request validation fail" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val upsertUserContactRequest = arbitrarySample[smithy.UpsertUserContactRequest]

        buildUserContactsService(
          authedUser = authedUser,
          userRepositoryMaybeError = Some(new RuntimeException("Repository error")),
        )
          .upsertContacts(Set(upsertUserContactRequest))
          .zioError shouldBe a[smithy.InternalServerError]

        upsertUserContactsCounterRef.get.zioValue shouldBe 0
      }
    }
  }

  trait TestContext {
    val upsertUserContactsCounterRef = Ref.make(0).zioValue

    def buildUserContactsService(
        authedUser: AuthedUser,
        userRepositoryMaybeError: Option[Throwable] = None,
    ): smithy.UserContactsService[Task] =
      ZIO
        .service[smithy.UserContactsService[Task]]
        .provide(
          UserContactsService.live,
          userContactsRepositoryMockLive(upsertUserContactsCounterRef, userRepositoryMaybeError),
          authorizationStateMockLive(authedUser),
          phoneNumberValidatorMockLive(),
          UserContactsValidators.upsertUserContactsValidatorLive,
        )
        .zioValue
  }
}
