package io.rikkos.domain.waha

sealed abstract class GroupParticipantError(val errorCode: Int)

object GroupParticipantError {

  case object NoError extends GroupParticipantError(0)

  /* Not found error code indicates that either the group or the participant does not exist.
   * This is considered a non-retryable error because retrying will not change the fact that
   * the group or participant is missing.
   *
   * Example scenarios:
   * 1. Trying to promote participant in a group that doesn't belong.
   */
  case object NotFound extends GroupParticipantError(404)

  /* Already exists error code indicates that the participant is already in the group.
   * This is considered a non-retryable error because retrying will not change the fact that
   * the participant is already a member of the group.
   *
   * Example scenarios:
   * 1. Trying to add a participant who is already in the group.
   */
  case object AlreadyExists extends GroupParticipantError(409)

  case class Unknown(override val errorCode: Int) extends GroupParticipantError(errorCode)

  val nonRetryableErrors: Set[GroupParticipantError] = Set(
    NoError,
    NotFound,
    AlreadyExists,
  )

  def apply(errorCode: Int): GroupParticipantError = errorCode match {
    case 0     => NoError
    case 404   => NotFound
    case 409   => AlreadyExists
    case other => Unknown(other)
  }
}
