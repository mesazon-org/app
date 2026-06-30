package io.mesazon.gateway.utils

import io.github.iltotore.iron.{Pure, RefinedType}
import zio.stream.ZStream

object FileByteStreamScanned extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type FileByteStreamScanned = FileByteStreamScanned.T

object ImageNormalizedByteStream extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type ImageNormalizedByteStream = ImageNormalizedByteStream.T

object ImageOriginalByteStream extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type ImageOriginalByteStream = ImageOriginalByteStream.T

type NormalizeResult =
  (imageOriginalByteStream: ImageOriginalByteStream, imageNormalizedByteStream: ImageNormalizedByteStream)
