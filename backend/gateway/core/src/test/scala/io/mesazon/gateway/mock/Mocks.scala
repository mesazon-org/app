package io.mesazon.gateway.mock

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClient
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.utils.*
import zio.*

object Mocks {

  final class AIClientMock[A](
      val extractFromImageCallsRef: Ref[List[(FileScannedPath, SupportedMediaType, String)]],
      val extractFromCsvCallsRef: Ref[List[(CsvValidatedPath, String)]],
      extractFromImageOptResult: Option[IO[ServiceError, A]] = None,
      extractFromCsvOptResult: Option[IO[ServiceError, NonEmptyChunk[A]]] = None,
  ) extends AIClient {
    override def extractFromImage[B](
        imageScannedPath: FileScannedPath,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractFromImageCallsRef.update(_ :+ (imageScannedPath, supportedMediaType, instructions)) *>
        extractFromImageOptResult
          .getOrElse(ZIO.die(new NotImplementedError("extractFromImage should not be called")))
          .map(_.asInstanceOf[B])

    override def extractFromCsv[B](
        csvValidatedPath: CsvValidatedPath,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, NonEmptyChunk[B]] =
      extractFromCsvCallsRef.update(_ :+ (csvValidatedPath, instructions)) *>
        extractFromCsvOptResult
          .getOrElse(
            extractFromImageOptResult
              .map(_.map(NonEmptyChunk.single))
              .getOrElse(ZIO.die(new NotImplementedError("extractFromCsv should not be called")))
          )
          .map(_.asInstanceOf[NonEmptyChunk[B]])

  }
}
