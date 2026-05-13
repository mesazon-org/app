package io.mesazon.testkit.base

import org.scalamock.handlers.CallHandler
import zio.*
import zio.config.typesafe.TypesafeConfigProvider
import zio.logging.backend.SLF4J

trait ZIOTestOps {

  private val loggerLayer = Runtime.removeDefaultLoggers >>>
    SLF4J.slf4j ++ Runtime.setConfigProvider(TypesafeConfigProvider.fromResourcePath())

  private val configProvider = TypesafeConfigProvider.fromResourcePath()

  val counterRef = Ref.make(0)

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

  implicit class RichIOHandler[E, A](val handler: CallHandler[IO[E, A]]) {

    def returnsZIO(value: A): handler.Derived =
      handler.returns(ZIO.succeed(value))

    def returningZIO(value: A): handler.Derived =
      returnsZIO(value)

    def returnsZIOUnit(implicit ev: Unit <:< A): handler.Derived =
      handler.returns(ZIO.unit.map(ev))

    def returningZIOUnit(implicit ev: Unit <:< A): handler.Derived =
      returnsZIOUnit

    def failsZIO(error: E): handler.Derived =
      handler.returns(ZIO.fail(error))

    def failingZIO(error: E): handler.Derived =
      failsZIO(error)

    def diesZIO(error: Throwable): handler.Derived =
      handler.returns(ZIO.die(error))

    def dyingZIO(error: Throwable): handler.Derived =
      diesZIO(error)
  }

  implicit class RichUIOHandler[A](val handler: CallHandler[UIO[A]]) {

    def returnsZIO(value: A): handler.Derived =
      handler.returns(ZIO.succeed(value))

    def returningZIO(value: A): handler.Derived =
      returnsZIO(value)

    def diesZIO(error: Throwable): handler.Derived =
      handler.returns(ZIO.die(error))

    def dyingZIO(error: Throwable): handler.Derived =
      diesZIO(error)
  }
}
