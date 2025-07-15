package io.rikkos.testkit.base

import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

trait ZIOTestOps {

  private val loggerLayer = Runtime.removeDefaultLoggers >>>
    SLF4J.slf4j ++ Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val configProvider = TypesafeConfigProvider.fromResourcePath()

  extension [E, A](zio: IO[E, A]) {
    private def zioEnv: IO[E, A] = zio
      .provideLayer(loggerLayer)
      .withConfigProvider(configProvider)

    def zioEither: Either[E, A] =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run[Nothing, Either[E, A]](zioEnv.either)
          .getOrThrowFiberFailure()
      }

    def zioValue: A =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run[E, A](zioEnv)
          .getOrThrowFiberFailure()
      }

    def zioCause: Cause[E] =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run[Nothing, Cause[E]](
            zioEnv.cause
          )
          .getOrThrowFiberFailure()
      }

    def zioError: E =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run[Nothing, Either[E, A]](zioEnv.either)
          .getOrThrowFiberFailure()
      }.swap.toOption.get
  }

  extension [A](ref: Ref[A]) {
    def refValue: A =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run(ref.get).getOrThrowFiberFailure()
      }
  }
}
