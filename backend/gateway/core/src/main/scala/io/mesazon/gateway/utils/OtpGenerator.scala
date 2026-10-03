package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import zio.*

trait OtpGenerator {
  def generateOtp: UIO[Otp]
}

object OtpGenerator {

  private final class OtpGeneratorImpl extends OtpGenerator {
    private inline val minInclusiveCharPerEach = 2
    private inline val maxExclusiveCharPerEach = 5
    private inline val maxChars                = 6
    private inline val letters                 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private inline val digits                  = "0123456789"

    override def generateOtp: UIO[Otp] = (for {
      random        <- ZIO.random
      randomLetters <- random.nextIntBetween(minInclusiveCharPerEach, maxExclusiveCharPerEach)
      randomDigits = maxChars - randomLetters
      shuffledLetters <- random.shuffle(letters.toList)
      shuffledDigits  <- random.shuffle(digits.toList)
      lettersAndDigits = shuffledLetters.take(randomLetters) ++ shuffledDigits.take(randomDigits)
      shuffledLettersAndDigits <- random.shuffle(lettersAndDigits)
      otp                      <- ZIO.attempt(Otp.applyUnsafe(shuffledLettersAndDigits.mkString))
    } yield otp).orDie
  }

  def observed(otpGenerator: OtpGenerator): OtpGenerator = otpGenerator

  val live = ZLayer.succeed(new OtpGeneratorImpl) >>> ZLayer.fromFunction(observed)
}
