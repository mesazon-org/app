package io.rikkos.gateway.config

case class ReplyingToMessagesCronJobConfig(
    triggerEverySeconds: Int,
    lastUpdateOffsetSeconds: Int,
    maxNumberContextMessages: Int,
)

object ReplyingToMessagesCronJobConfig {
  val live = deriveConfigLayer[ReplyingToMessagesCronJobConfig]("replying-to-messages-cron-job")
}
