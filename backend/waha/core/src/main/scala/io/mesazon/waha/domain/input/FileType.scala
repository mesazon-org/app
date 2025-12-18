package io.mesazon.waha.domain.input

import io.mesazon.waha.domain.*

sealed trait FileType

object FileType {
  case class Url(mimeType: FileTypeMimeType, fileName: FileTypeFileName, url: FileTypeURL) extends FileType

  case class Data(mimeType: FileTypeMimeType, fileName: FileTypeFileName, data: FileTypeData) extends FileType
}
