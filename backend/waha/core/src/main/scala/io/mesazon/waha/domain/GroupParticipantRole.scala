package io.mesazon.waha.domain

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

sealed trait GroupParticipantRole

object GroupParticipantRole {

  case object SuperAdmin            extends GroupParticipantRole
  case object Admin                 extends GroupParticipantRole
  case object Participant           extends GroupParticipantRole
  case object LeftGroup             extends GroupParticipantRole
  case class Unknown(value: String) extends GroupParticipantRole

  given JsonValueCodec[GroupParticipantRole] = new JsonValueCodec[GroupParticipantRole] {

    override def decodeValue(in: JsonReader, default: GroupParticipantRole): GroupParticipantRole =
      in.readString(null).trim.toLowerCase match {
        case "superadmin"  => SuperAdmin
        case "admin"       => Admin
        case "participant" => Participant
        case "left"        => LeftGroup
        case other         => Unknown(other)
      }

    override def encodeValue(x: GroupParticipantRole, out: JsonWriter): Unit =
      x match {
        case LeftGroup      => out.writeVal("left")
        case Admin          => out.writeVal("admin")
        case SuperAdmin     => out.writeVal("superadmin")
        case Participant    => out.writeVal("participant")
        case Unknown(value) => out.writeVal(value)
      }

    override def nullValue: GroupParticipantRole = null
  }
}
