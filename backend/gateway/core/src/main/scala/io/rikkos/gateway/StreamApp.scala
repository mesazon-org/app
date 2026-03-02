package io.rikkos.gateway

import io.rikkos.gateway.stream.ReplyingToMessagesCronJobStream
import zio.*

object StreamApp {

  def streamsLayer = ZLayer(
    for {
      replyingToMessagesCronJobStream <- ZIO.service[ReplyingToMessagesCronJobStream]
      _                               <- replyingToMessagesCronJobStream.stream.runDrain
    } yield ()
  )
}
