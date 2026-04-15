package io.mesazon.gateway.json

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import io.github.iltotore.iron.jsoniter.given
import io.mesazon.domain.gateway.*
import sttp.tapir.Schema
import sttp.tapir.codec.iron.given

given Schema[AssistantResponse] = Schema.derived[AssistantResponse]

given JsonValueCodec[AssistantResponse] = JsonCodecMaker.make[AssistantResponse]
