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
      val noteCompactionCallsRef: Ref[List[(Long, Long, List[Long], List[Long], List[String], String)]],
      extractFromImageOptResult: Option[IO[ServiceError, A]] = None,
      extractFromCsvOptResult: Option[IO[ServiceError, NonEmptyChunk[A]]] = None,
      noteCompactionOptResult: Option[IO[ServiceError, NoteCompactionOutput]] = None,
  ) extends AIClientDataExtraction {
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
        csvRecordStream: ZStream[Scope, ServiceError, CSVRecord],
        instructions: String,
    )(using OpenAIJsonSchema[B], JsonValueCodec[B]): ZIO[Scope, ServiceError, NonEmptyChunk[B]] =
      for {
        csvRecords <- csvRecordStream.runCollect
        _          <- extractFromCsvCallsRef.update(_ :+ (csvRecords.toList, instructions))
        result     <- extractFromCsvOptResult
          .getOrElse(
            extractFromImageOptResult
              .map(_.map(NonEmptyChunk.single))
              .getOrElse(ZIO.die(new NotImplementedError("extractFromCsv should not be called")))
          )
          .map(_.asInstanceOf[NonEmptyChunk[B]])
      } yield result

    override def noteCompaction(
        entriesIdentified: Long,
        entriesProcessed: Long,
        emptyEntryRows: List[Long],
        unidentifiedEntryRows: List[Long],
        unidentifiedEntriesNotes: List[String],
        instructions: String,
    ): IO[ServiceError, NoteCompactionOutput] =
      noteCompactionCallsRef.update(
        _ :+ (
          entriesIdentified,
          entriesProcessed,
          emptyEntryRows,
          unidentifiedEntryRows,
          unidentifiedEntriesNotes,
          instructions,
        )
      ) *>
        noteCompactionOptResult
          .getOrElse(ZIO.die(new NotImplementedError("noteCompaction should not be called")))

  }
}
