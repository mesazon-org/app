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
      val extractFromImageCallsRef: Ref[List[(FileByteStreamScanned, SupportedMediaType, String)]],
      val extractFromCsvCallsRef: Ref[List[(ValidatedCsvByteStream, String)]],
  ) extends AIClient {
    override def extractFromImage[B](
        imageByteStream: FileByteStreamScanned,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractFromImageCallsRef.update(_ :+ (imageByteStream, supportedMediaType, instructions)) *>
        extractResult.map(_.asInstanceOf[B])

    override def extractFromCsv[B](
        csvByteStream: ValidatedCsvByteStream,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractFromCsvCallsRef.update(_ :+ (csvByteStream, instructions)) *>
        extractResult.map(_.asInstanceOf[B])
  }

  final class AIClientPathMock[A](
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

    override def extractFromImage[B](
        imageByteStream: FileByteStreamScanned,
        supportedMediaType: SupportedMediaType,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      ZIO.die(new NotImplementedError("AIClient.extractFromImage stream operation should not be called"))

    override def extractFromCsv[B](
        csvByteStream: ValidatedCsvByteStream,
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      ZIO.die(new NotImplementedError("AIClient.extractFromCsv stream operation should not be called"))
  }
}
