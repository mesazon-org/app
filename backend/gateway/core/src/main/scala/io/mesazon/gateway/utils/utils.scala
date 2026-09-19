package io.mesazon.gateway.utils

import io.github.iltotore.iron.{Pure, RefinedType}
import io.mesazon.domain.gateway.*
import zio.stream.ZStream

import java.nio.file.Path

object FileByteStreamScanned extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type FileByteStreamScanned = FileByteStreamScanned.T

object FileScannedPath extends RefinedType[Path, Pure]
type FileScannedPath = FileScannedPath.T

object CsvValidatedPath extends RefinedType[Path, Pure]
type CsvValidatedPath = CsvValidatedPath.T

object ImageNormalizedByteStream extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type ImageNormalizedByteStream = ImageNormalizedByteStream.T

object ImageOriginalByteStream extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type ImageOriginalByteStream = ImageOriginalByteStream.T

type FileScannerScanOutput =
  (fileScannedPath: FileScannedPath, supportedMediaType: SupportedMediaType, fileBytesSize: FileBytesSize)

type NormalizeResult =
  (imageOriginalByteStream: ImageOriginalByteStream, imageNormalizedByteStream: ImageNormalizedByteStream)
