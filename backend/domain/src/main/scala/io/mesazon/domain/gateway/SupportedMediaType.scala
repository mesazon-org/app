package io.mesazon.domain.gateway

enum SupportedMediaType(val ext: String, val mime: String) {
  case PNG  extends SupportedMediaType("png", "image/png")
  case JPEG extends SupportedMediaType("jpg", "image/jpeg")
  case WEBP extends SupportedMediaType("webp", "image/webp")
}

object SupportedMediaType {
  val images: List[SupportedMediaType] = List(PNG, JPEG, WEBP)
}
