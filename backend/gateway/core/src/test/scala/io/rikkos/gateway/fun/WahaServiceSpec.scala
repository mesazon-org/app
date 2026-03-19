package io.rikkos.gateway.fun

import io.mesazon.testkit.base.ZWordSpecBase
import io.rikkos.gateway.mock.*
import io.rikkos.gateway.service.WahaService
import io.rikkos.gateway.smithy
import io.rikkos.gateway.utils.SmithyArbitraries
import io.rikkos.gateway.validation.WahaValidator
import zio.*

class WahaServiceSpec extends ZWordSpecBase, SmithyArbitraries {

  "WahaService" when {
    "wahaWebhookMessage" should {
      "successfully process the webhook message" in new TestContext {
        val wahaWebhookMessageRequest = arbitrarySample[smithy.WahaMessageTextRequest]
        val wahaService               = buildUserManagementService()

        wahaService
          .wahaWebhookMessage(wahaWebhookMessageRequest)
          .zioEither
          .isRight shouldBe true
      }
    }
  }

  trait TestContext {

    def buildUserManagementService(
    ): smithy.WahaService[Task] =
      ZIO
        .service[smithy.WahaService[Task]]
        .provide(
          WahaService.live,
          wahaRepositoryMockLive(),
          WahaValidator.wahaMessageRequestValidatorLive,
          wahaPhoneNumberValidatorMockLive(),
        )
        .zioValue
  }
}
