package io.mesazon.domain.waha.output

import io.mesazon.domain.waha.*

case class GroupsCreateOutput(
    groupID: GroupID,
    inviteUrl: GroupInviteUrl,
    nonRegisteredUserAccountIDs: List[UserAccountID],
)
