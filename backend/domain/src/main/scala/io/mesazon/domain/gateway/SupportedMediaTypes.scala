package io.mesazon.domain.gateway

enum SupportedMediaTypes(val ext: String, val mime: String) {
  case PNG  extends SupportedMediaTypes("png", "image/png")
  case JPEG extends SupportedMediaTypes("jpg", "image/jpeg")
  case WEBP extends SupportedMediaTypes("webp", "image/webp")
}

object SupportedMediaTypes {
  val images: List[SupportedMediaTypes] = List(PNG, JPEG, WEBP)
}
