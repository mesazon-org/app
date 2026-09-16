package io.mesazon.domain.gateway

import cats.data.NonEmptySet
import cats.implicits.*

enum SupportedMediaType(val extensions: NonEmptySet[String], val mimes: NonEmptySet[String]) {
  case PNG  extends SupportedMediaType(NonEmptySet.one("png"), NonEmptySet.one("image/png"))
  case JPEG extends SupportedMediaType(NonEmptySet.of("jpg", "jpeg"), NonEmptySet.one("image/jpeg"))
  case WEBP extends SupportedMediaType(NonEmptySet.one("webp"), NonEmptySet.one("image/webp"))
  case CSV  extends SupportedMediaType(NonEmptySet.one("csv"), NonEmptySet.of("text/csv", "text/plain"))
  case XLS  extends SupportedMediaType(NonEmptySet.one("xls"), NonEmptySet.one("application/vnd.ms-excel"))
  case XLSX
      extends SupportedMediaType(
        NonEmptySet.one("xlsx"),
        NonEmptySet.one("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
      )
}

object SupportedMediaType {
  val images: List[SupportedMediaType]       = List(PNG, JPEG, WEBP)
  val spreadsheets: List[SupportedMediaType] = List(CSV, XLS, XLSX)
  val excel: List[SupportedMediaType]        = List(XLS, XLSX)
  val csv: List[SupportedMediaType]          = List(CSV)
}
