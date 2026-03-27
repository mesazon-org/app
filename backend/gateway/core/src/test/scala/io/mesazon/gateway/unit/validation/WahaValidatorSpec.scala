package io.mesazon.gateway.unit.validation

import io.mesazon.domain.gateway.*
import io.mesazon.domain.gateway.ServiceError.BadRequestError.InvalidFieldError
import io.mesazon.domain.waha
import io.mesazon.gateway.Mocks.*
import io.mesazon.gateway.smithy
import io.mesazon.gateway.smithy.{InternalData, InternalInfo}
import io.mesazon.gateway.utils.SmithyArbitraries
import io.mesazon.gateway.validation.*
import io.mesazon.testkit.base.{IronRefinedTypeArbitraries, ZWordSpecBase}
import zio.*

class WahaValidatorSpec extends ZWordSpecBase, SmithyArbitraries, IronRefinedTypeArbitraries {

  "WahaValidator" when {
    "wahaMessageRequest" should {
      "succeed return the validate data" in {
        val wahaUserAccountID = arbitrarySample[waha.UserAccountID]
        val expected          = arbitrarySample[WahaMessage]
          .copy(
            wahaChatID = waha.ChatID.fromUserAccountID(wahaUserAccountID),
            wahaUserAccountID = wahaUserAccountID,
            wahaWhatsAppPhoneNumber = waha.WhatsAppPhoneNumber.fromUserAccountID(wahaUserAccountID),
            phoneNumber = PhoneNumberE164.assume(s"+${waha.WahaPhone.fromUserAccountID(wahaUserAccountID)}"),
          )
        val data = smithy.WahaMessageTextRequest(
          payload = smithy.Payload(
            id = expected.wahaMessageID.value,
            from = expected.wahaUserAccountID.value,
            body = expected.wahaMessageText.value,
            data = InternalData(info =
              InternalInfo(
                sender = expected.wahaUserID.value,
                senderAlt = expected.wahaWhatsAppPhoneNumber.value,
                pushName = expected.wahaFullName.value,
              )
            ),
          )
        )

        val validator = ZIO
          .service[ServiceValidator[smithy.WahaMessageTextRequest, WahaMessage]]
          .provide(
            WahaValidator.wahaMessageRequestValidatorLive,
            wahaPhoneNumberValidatorMockLive(),
          )
          .zioValue

        val result = validator.validate(data).zioValue

        result shouldBe expected
      }

      "succeed return the validate data when only wahaUserID and wahaUserAccountID is present" in {
        val wahaUserAccountID = arbitrarySample[waha.UserAccountID]
        val expected          = arbitrarySample[WahaMessage]
          .copy(
            wahaChatID = waha.ChatID.fromUserAccountID(wahaUserAccountID),
            wahaUserAccountID = wahaUserAccountID,
            wahaWhatsAppPhoneNumber = waha.WhatsAppPhoneNumber.fromUserAccountID(wahaUserAccountID),
            phoneNumber = PhoneNumberE164.assume(s"+${waha.WahaPhone.fromUserAccountID(wahaUserAccountID)}"),
          )
        val data = smithy.WahaMessageTextRequest(
          payload = smithy.Payload(
            id = expected.wahaMessageID.value,
            from = expected.wahaUserID.value,
            body = expected.wahaMessageText.value,
            data = InternalData(info =
              InternalInfo(
                sender = expected.wahaUserAccountID.value,
                senderAlt = expected.wahaUserID.value,
                pushName = expected.wahaFullName.value,
              )
            ),
          )
        )

        val validator = ZIO
          .service[ServiceValidator[smithy.WahaMessageTextRequest, WahaMessage]]
          .provide(
            WahaValidator.wahaMessageRequestValidatorLive,
            wahaPhoneNumberValidatorMockLive(),
          )
          .zioValue

        val result = validator.validate(data).zioValue

        result shouldBe expected
      }

      "fail to validated and accumulate errors" in {
        val data = smithy.WahaMessageTextRequest(
          payload = smithy.Payload(
            id = "",
            from = "123",
            body = "",
            data = InternalData(info =
              InternalInfo(
                sender = "456",
                senderAlt = "789",
                pushName = "",
              )
            ),
          )
        )

        val validator = ZIO
          .service[ServiceValidator[smithy.WahaMessageTextRequest, WahaMessage]]
          .provide(
            WahaValidator.wahaMessageRequestValidatorLive,
            wahaPhoneNumberValidatorMockLive(),
          )
          .zioValue

        val result = validator.validate(data).zioError

        val expected = Seq(
          InvalidFieldError(
            "messageID",
            "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
            Seq(""),
          ),
          InvalidFieldError(
            "userID",
            "Should not have leading or trailing whitespaces & All letters should be lower cased & Should have a minimum length of 1 & Should end with @lid",
            Seq("123", "456", "789"),
          ),
          InvalidFieldError(
            "chatID",
            "Should not have leading or trailing whitespaces & All letters should be lower cased & Should have a minimum length of 1 & Should end with @c.us",
            Seq("123", "456", "789"),
          ),
          InvalidFieldError(
            "userAccountID",
            "Should not have leading or trailing whitespaces & All letters should be lower cased & Should have a minimum length of 1 & Should end with @c.us",
            Seq("123", "456", "789"),
          ),
          InvalidFieldError(
            "whatsAppPhoneNumber",
            "Should not have leading or trailing whitespaces & All letters should be lower cased & Should have a minimum length of 1 & Should end with @s.whatsapp.net",
            Seq("123", "456", "789"),
          ),
          InvalidFieldError(
            "fullName",
            "Should not have leading or trailing whitespaces & Should have a minimum length of 1",
            Seq(""),
          ),
          InvalidFieldError(
            "messageText",
            "Should have a minimum length of 1",
            Seq(""),
          ),
          InvalidFieldError(
            "phoneNumber",
            "Empty user account id",
            Seq(""),
          ),
        )

        result
          .asInstanceOf[ServiceError.BadRequestError.FormValidationError]
          .invalidFields should contain theSameElementsAs expected
      }
    }
  }
}
