package io.mesazon.gateway.utils

import io.github.iltotore.iron.{Pure, RefinedType}
import io.mesazon.domain.gateway.*
import zio.stream.ZStream

object FileByteStreamScanned extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type FileByteStreamScanned = FileByteStreamScanned.T

object ImageNormalizedByteStream extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type ImageNormalizedByteStream = ImageNormalizedByteStream.T

object ImageOriginalByteStream extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type ImageOriginalByteStream = ImageOriginalByteStream.T

type FileScannerScanOutput =
  (fileByteStreamScanned: FileByteStreamScanned, supportedMediaType: SupportedMediaType, fileBytesSize: FileBytesSize)

type NormalizeResult =
  (imageOriginalByteStream: ImageOriginalByteStream, imageNormalizedByteStream: ImageNormalizedByteStream)
