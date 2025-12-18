package io.mesazon.waha.domain.output

import io.mesazon.waha.domain.*

case class GroupsCreateOutput(
    groupID: GroupID,
    inviteUrl: GroupInviteUrl,
    nonRegisteredUserAccountIDs: List[UserAccountID],
)
