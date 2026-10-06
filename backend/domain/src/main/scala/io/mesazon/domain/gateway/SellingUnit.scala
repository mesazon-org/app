package io.mesazon.domain.gateway

case class SellingUnit(
    quantity: SaleQuantity,
    packageType: Option[PackageType],
)

enum SaleQuantity {
  case Counted(
      count: SaleQuantityCount,
      eachMeasurement: Option[Measurement],
  )
  case FixedMeasurement(
      measurement: Measurement
  )
  case VariableMeasurement(
      unit: MeasurementUnit
  )
}

case class Measurement(
    value: MeasurementValue,
    unit: MeasurementUnit,
)
