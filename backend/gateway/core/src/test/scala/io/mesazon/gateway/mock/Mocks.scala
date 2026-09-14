package io.mesazon.gateway.mock

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.mesazon.domain.gateway.{ServiceError, SupportedMediaType}
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.utils.FileByteStreamScanned
import zio.*

object Mocks {

  final class AIClientMock[A](
      extractResult: IO[ServiceError, A],
      val extractCallsRef: Ref[List[(FileByteStreamScanned, SupportedMediaType, String)]],
  ) extends AIClient {
    override def extract[B](
        contentByteStream: FileByteStreamScanned,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractCallsRef.update(_ :+ (contentByteStream, supportedMediaType, instructions)) *>
        extractResult.map(_.asInstanceOf[B])
  }
}
