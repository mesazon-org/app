package io.mesazon.gateway.utils

import com.google.i18n.phonenumbers.PhoneNumberUtil as LibPhoneNumberUtil
import zio.ZLayer

object PhoneNumberUtil {
  val live = ZLayer.succeed(LibPhoneNumberUtil.getInstance())
}
