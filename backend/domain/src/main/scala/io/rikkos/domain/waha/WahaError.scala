package io.rikkos.domain.waha

case class WahaError(errorCode: WahaErrorCode, message: Option[String], underlying: Option[Throwable] = None)
    extends Throwable(s"$errorCode${message.map(m => s": $m").getOrElse("")}", underlying.orNull)

object WahaError {

  def unexpectedError(message: String, underlying: Option[Throwable] = None): WahaError =
    WahaError(WahaErrorCode.UNEXPECTED_ERROR, Some(message), underlying)
}
