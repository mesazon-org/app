package io.mesazon.domain.gateway

case class FieldInfo(path: String)

object FieldInfo {
  def fromPathSegments(pathSegments: (String | Int)*): FieldInfo =
    FieldInfo(
      pathSegments.map {
        case pathSegmentProperty: String => s"/${pathSegmentProperty.replace("~", "~0").replace("/", "~1")}"
        case pathSegmentIndex: Int       => s"/$pathSegmentIndex"
      }.mkString
    )
}
