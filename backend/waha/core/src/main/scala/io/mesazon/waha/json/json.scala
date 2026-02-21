package io.mesazon.waha

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import io.rikkos.domain.waha.GroupParticipantRole

package object json {

  given JsonValueCodec[GroupParticipantRole] = new JsonValueCodec[GroupParticipantRole] {
    override def decodeValue(in: JsonReader, default: GroupParticipantRole): GroupParticipantRole =
      in.readString(null).trim.toLowerCase match {
        case "superadmin"  => GroupParticipantRole.SuperAdmin
        case "admin"       => GroupParticipantRole.Admin
        case "participant" => GroupParticipantRole.Participant
        case "left"        => GroupParticipantRole.LeftGroup
        case other         => GroupParticipantRole.Unknown(other)
      }

    override def encodeValue(x: GroupParticipantRole, out: JsonWriter): Unit =
      x match {
        case GroupParticipantRole.LeftGroup      => out.writeVal("left")
        case GroupParticipantRole.Admin          => out.writeVal("admin")
        case GroupParticipantRole.SuperAdmin     => out.writeVal("superadmin")
        case GroupParticipantRole.Participant    => out.writeVal("participant")
        case GroupParticipantRole.Unknown(value) => out.writeVal(value)
      }

    override def nullValue: GroupParticipantRole = null
  }

}
