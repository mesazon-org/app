package io.mesazon.gateway.utils

import io.github.iltotore.iron.{Pure, RefinedType}
import zio.stream.ZStream

object FileBytesScanned extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type FileBytesScanned = FileBytesScanned.T

object OriginalLogoProcessed extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type OriginalLogoProcessed = OriginalLogoProcessed.T

object NormalizedLogoProcessed extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type NormalizedLogoProcessed = NormalizedLogoProcessed.T

object WhatsAppLogoProcessed extends RefinedType[ZStream[Any, Throwable, Byte], Pure]
type WhatsAppLogoProcessed = WhatsAppLogoProcessed.T
