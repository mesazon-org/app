package io.mesazon.testkit.base

import org.scalactic.source.Position
import org.scalamock.handlers.CallHandler
import org.scalatest.exceptions.TestFailedException
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

    private def runOrRethrow: A =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe.run[E, A](zio) match {
          case Exit.Success(value) => value
          case Exit.Failure(cause) =>
            throw cause.dieOption
              .orElse(cause.failureOption.collect { case throwable: Throwable => throwable })
              .getOrElse(FiberFailure(cause))
        }
      }

    def zioEither: Either[E, A] = zioEnv.either.runOrRethrow

    def zioValue: A = zioEnv.runOrRethrow

    def zioCause: Cause[E] =
      Unsafe.unsafe { implicit unsafe =>
        Runtime.default.unsafe
          .run[Nothing, Cause[E]](
            zioEnv.cause
          )
          .getOrThrowFiberFailure()
      }

    def zioError(using position: Position): E =
      zioEnv.either.runOrRethrow match {
        case Left(error)  => error
        case Right(value) =>
          throw new TestFailedException(
            _ => Some(s"Expected the effect to fail, but it succeeded with: $value"),
            None,
            position,
          )
      }
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
