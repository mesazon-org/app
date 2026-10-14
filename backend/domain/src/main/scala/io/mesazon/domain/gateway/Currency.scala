package io.mesazon.domain.gateway

enum Currency(val decimalPoints: Option[Int]) {
  case EUR extends Currency(Some(Currency.StandardDecimalPoints))
  case USD extends Currency(Some(Currency.StandardDecimalPoints))
  case GBP extends Currency(Some(Currency.StandardDecimalPoints))
}

object Currency {

  private inline val StandardDecimalPoints = 2
}
