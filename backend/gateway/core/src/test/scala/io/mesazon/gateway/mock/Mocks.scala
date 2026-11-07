package io.mesazon.gateway.mock

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import io.mesazon.domain.gateway.*
import io.mesazon.gateway.clients.AIClientDataExtraction
import io.mesazon.gateway.json.OpenAIJsonSchema
import io.mesazon.gateway.utils.*
import org.apache.commons.csv.CSVRecord
import zio.*
import zio.stream.*

object Mocks {

  final class AIClientDataExtractionMock[A](
      val extractFromImageCallsRef: Ref[List[(FileScannedPath, SupportedMediaType, String)]],
      val extractFromCsvCallsRef: Ref[List[(List[CSVRecord], String)]],
      extractFromImageOptResult: Option[IO[ServiceError, A]] = None,
      extractFromCsvOptResult: Option[IO[ServiceError, NonEmptyChunk[A]]] = None,
      extractFromImageProcessingMessagesRefOpt: Option[Ref[List[Option[String]]]] = None,
      extractFromCsvProcessingMessagesRefOpt: Option[Ref[List[Option[String]]]] = None,
      noteCompactionCallsRefOpt: Option[Ref[List[(List[String], String)]]] = None,
      noteCompactionOptResult: Option[IO[ServiceError, NoteCompactionOutput]] = None,
  ) extends AIClientDataExtraction {
    override def extractFromImage[B](
        imageScannedPath: FileScannedPath,
        supportedMediaType: SupportedMediaType,
        instructions: String,
        processingMessageOpt: Option[String],
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): IO[ServiceError, B] =
      extractFromImageCallsRef.update(_ :+ (imageScannedPath, supportedMediaType, instructions)) *>
        ZIO.foreachDiscard(extractFromImageProcessingMessagesRefOpt)(_.update(_ :+ processingMessageOpt)) *>
        extractFromImageOptResult
          .getOrElse(ZIO.die(new NotImplementedError("extractFromImage should not be called")))
          .map(_.asInstanceOf[B])

    override def extractFromCsv[B](
        csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
        instructions: String,
        processingMessageOpt: Option[String],
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): ZIO[Scope, ServiceError, NonEmptyChunk[B]] =
      for {
        csvRecords <- csvRecordStream.runCollect
        _          <- extractFromCsvCallsRef.update(_ :+ (csvRecords.toList, instructions))
        _          <- ZIO.foreachDiscard(extractFromCsvProcessingMessagesRefOpt)(_.update(_ :+ processingMessageOpt))
        result     <- extractFromCsvOptResult
          .getOrElse(
            extractFromImageOptResult
              .map(_.map(NonEmptyChunk.single))
              .getOrElse(ZIO.die(new NotImplementedError("extractFromCsv should not be called")))
          )
          .map(_.asInstanceOf[NonEmptyChunk[B]])
      } yield result

    override def noteCompaction(notes: List[String], instructions: String): IO[ServiceError, NoteCompactionOutput] =
      ZIO.foreachDiscard(noteCompactionCallsRefOpt)(_.update(_ :+ (notes, instructions))) *>
        noteCompactionOptResult.getOrElse(ZIO.die(new NotImplementedError("noteCompaction should not be called")))
  }
}
