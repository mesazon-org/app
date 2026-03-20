package io.mesazon.gateway

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.AssistantResponse
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given

package object json {

  given Schema[AssistantResponse] = Schema.derived

  given JsonValueCodec[AssistantResponse] = JsonCodecMaker.make[AssistantResponse]
}
