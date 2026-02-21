package io.rikkos.domain.waha

sealed trait GroupParticipantRole

object GroupParticipantRole {

  case object SuperAdmin            extends GroupParticipantRole
  case object Admin                 extends GroupParticipantRole
  case object Participant           extends GroupParticipantRole
  case object LeftGroup             extends GroupParticipantRole
  case class Unknown(value: String) extends GroupParticipantRole
}
