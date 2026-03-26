package io.mesazon.gateway.fun

import io.mesazon.gateway.StreamApp
import io.mesazon.gateway.stream.ReplyingToMessagesCronJobStream
import io.mesazon.testkit.base.ZWordSpecBase
import zio.*
import zio.stream.*

class StreamAppSpec extends ZWordSpecBase {

  def replyingToMessagesCronJobStreamLayer(
      ref: Ref[Int],
      maybeError: Option[Throwable],
  ): ULayer[ReplyingToMessagesCronJobStream] =
    ZLayer.succeed(new ReplyingToMessagesCronJobStream {
      override def stream: Stream[Throwable, Unit] =
        ZStream.fromZIO(
          maybeError
            .fold(
              ref.incrementAndGet.unit
            )(error => ref.incrementAndGet *> ZIO.fail(error))
        )
    })

  "StreamApp" should {
    "keep the stream running even in case of failure" in {
      val replyingToMessagesCronJobStreamRef = Ref.make(0).zioValue
      val streamApp                          =
        replyingToMessagesCronJobStreamLayer(
          replyingToMessagesCronJobStreamRef,
          Some(new RuntimeException("Boom")),
        ) >>> StreamApp.streamsLayer

      (for {
        fork <- streamApp.launch.fork
        _    <- ZIO.sleep(3.seconds)
        _    <- fork.interrupt
      } yield ()).zioValue

      replyingToMessagesCronJobStreamRef.get.zioValue should be > 1
    }
  }
}
