package io.mesazon.gateway.fun

import io.mesazon.gateway.service.WahaService
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.gateway.validation.WahaValidator
import io.mesazon.gateway.{smithy, Mocks}
import io.mesazon.testkit.base.ZWordSpecBase
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
          Mocks.wahaRepositoryLive(),
          WahaValidator.wahaMessageRequestValidatorLive,
          Mocks.wahaPhoneNumberValidatorLive(),
        )
        .zioValue
  }
}
