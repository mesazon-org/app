package io.mesazon.domain.gateway

enum SupportedMediaType(val ext: String, val mime: String) {
  case PNG           extends SupportedMediaType("png", "image/png")
  case JPEG          extends SupportedMediaType("jpg", "image/jpeg")
  case WEBP          extends SupportedMediaType("webp", "image/webp")
  case CSV           extends SupportedMediaType("csv", "text/csv")
  case PLAINTEXT_CSV extends SupportedMediaType("csv", "text/plain")
  case XLS           extends SupportedMediaType("xls", "application/vnd.ms-excel")
  case XLSX extends SupportedMediaType("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
}

object SupportedMediaType {
  val images: List[SupportedMediaType]       = List(PNG, JPEG, WEBP)
  val spreadsheets: List[SupportedMediaType] = List(CSV, PLAINTEXT_CSV, XLS, XLSX)
  val excel: List[SupportedMediaType]        = List(XLS, XLSX)
  val csv: List[SupportedMediaType]          = List(CSV, PLAINTEXT_CSV)
}
