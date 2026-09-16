package io.mesazon.gateway.mock

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.mesazon.domain.gateway.{ServiceError, SupportedMediaType}
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.utils.*
import zio.*

object Mocks {

  final class AIClientMock[A](
      extractResult: IO[ServiceError, A],
      val extractFromImageCallsRef: Ref[List[(FileScannedPath, SupportedMediaType, String)]],
      val extractFromCsvCallsRef: Ref[List[(CsvValidatedPath, String)]],
  ) extends AIClient {
    override def extractFromImage[B](
        imageScannedPath: FileScannedPath,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractFromImageCallsRef.update(_ :+ (imageScannedPath, supportedMediaType, instructions)) *>
        extractResult.map(_.asInstanceOf[B])

    override def extractFromCsv[B](
        csvValidatedPath: CsvValidatedPath,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractFromCsvCallsRef.update(_ :+ (csvValidatedPath, instructions)) *>
        extractResult.map(_.asInstanceOf[B])

  }
}
