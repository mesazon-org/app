package io.mesazon.gateway.utils

import io.mesazon.domain.gateway.*
import zio.*

trait OtpGenerator {
  def generate: UIO[Otp]
}

object OtpGenerator {

  private final class OtpGeneratorImpl extends OtpGenerator {
    inline private val minInclusiveCharPerEach = 2
    inline private val maxExclusiveCharPerEach = 5
    inline private val maxChars                = 6
    inline private val letters                 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    inline private val digits                  = "0123456789"

    override def generate: UIO[Otp] = (for {
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
