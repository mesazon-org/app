package io.mesazon.gateway

import io.mesazon.gateway.stream.ReplyingToMessagesCronJobStream
import zio.*
import zio.stream.ZStream

object StreamApp {

  def streamsLayer = ZLayer {
    for {
      replyingToMessagesCronJobStream <- ZIO.service[ReplyingToMessagesCronJobStream]
      _                               <- replyingToMessagesCronJobStream.stream.catchAllCause { cause =>
        ZStream.fromZIO(ZIO.logErrorCause("Stream crashed due to defect. Restarting in 1s...", cause))
          *> ZStream.fail(new RuntimeException("Fail stream", cause.failureOption.orNull))
      }
        .retry(Schedule.spaced(2.second) && Schedule.forever)
        .forever
        .runDrain
    } yield ()
  }
}
