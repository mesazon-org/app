package io.mesazon.gateway.fun

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.service.UserContactsService
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.gateway.validation.*
import io.mesazon.gateway.{smithy, Mocks}
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*

class UserContactsServiceSpec extends ZWordSpecBase, SmithyArbitraries {

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
          .asInstanceOf[smithy.BadRequest] shouldBe smithy.BadRequest(
          code = "INVALID_FIELDS_ERROR",
          fields = Some(List("displayName")),
        )

        upsertUserContactsCounterRef.get.zioValue shouldBe 0
      }

      "fail with InternalServerError when request validation fail" in new TestContext {
        val authedUser               = arbitrarySample[AuthedUser]
        val upsertUserContactRequest = arbitrarySample[smithy.UpsertUserContactRequest]

        buildUserContactsService(
          authedUser = authedUser,
          userManagementRepositoryMaybeError = Some(new RuntimeException("Repository error")),
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
        userManagementRepositoryMaybeError: Option[Throwable] = None,
    ): smithy.UserContactsService[Task] =
      ZIO
        .service[smithy.UserContactsService[Task]]
        .provide(
          UserContactsService.live,
          Mocks.userContactsRepositoryLive(upsertUserContactsCounterRef, userManagementRepositoryMaybeError),
          Mocks.authorizationStateLive(authedUser),
          Mocks.phoneNumberRegionValidatorLive(),
          UserContactsValidators.upsertUserContactsValidatorLive,
        )
        .zioValue
  }
}
