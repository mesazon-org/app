package io.mesazon.waha

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.rikkos.domain.waha.*
import sttp.client4.*
import sttp.client4.jsoniter.*
import sttp.model.*
import zio.*

object ContactsRequests {

  given JsonValueCodec[CheckIfExistsResponse] = JsonCodecMaker.make

  case class CheckIfExistsResponse(
      numberExists: Boolean,
      @named("chatId") userAccountID: Option[UserAccountID] = None,
  )

  def checkIfExists(
      baseUri: Uri,
      apiKeyHeader: Header,
      sessionID: SessionID,
      userAccountID: UserAccountID,
  )(using Backend[Task]): IO[WahaError, Response[CheckIfExistsResponse]] =
    basicRequest
      .get(
        baseUri
          .withPath("api", "contacts", "check-exists")
          .addParam("session", sessionID.value)
          .addParam("phone", userAccountID.value)
      )
      .headers(apiKeyHeader)
      .response(asJson[CheckIfExistsResponse])
      .standardSendRequest(WahaErrorCode.CONTACTS_CHECK_IF_EXISTS_ERROR)
}
