package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import io.mesazon.domain.waha
import io.mesazon.gateway.smithy
import io.mesazon.testkit.base.*
import org.scalacheck.*

trait SmithyArbitraries extends GatewayArbitraries, IronRefinedTypeTransformer {

  val genCyPhoneNationalNumber: Gen[String] = for {
    phoneNumberPrefix <- Gen.oneOf("99", "97", "96")
    phoneNumberSuffix <- Gen.choose(100000, 999999)
  } yield s"$phoneNumberPrefix$phoneNumberSuffix"

  given Arbitrary[smithy.WahaMessageTextRequest] = Arbitrary {
    for {
      wahaMessage <- Arbitrary.arbitrary[WahaMessage]
      request = smithy.WahaMessageTextRequest(payload =
        smithy.Payload(
          id = wahaMessage.wahaMessageID.value,
          from = wahaMessage.wahaUserAccountID.value,
          body = "Body",
          data = smithy.InternalData(
            smithy.InternalInfo(
              sender = waha.WhatsAppPhoneNumber.fromUserAccountID(wahaMessage.wahaUserAccountID).value,
              senderAlt = wahaMessage.wahaUserID.value,
              pushName = wahaMessage.wahaFullName.value,
            )
          ),
        )
      )
    } yield request
  }

}
