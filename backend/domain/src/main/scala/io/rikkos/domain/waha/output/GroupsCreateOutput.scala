package io.rikkos.domain.waha.output

import io.rikkos.domain.waha.*

case class GroupsCreateOutput(
    groupID: GroupID,
    inviteUrl: GroupInviteUrl,
    nonRegisteredUserAccountIDs: List[UserAccountID],
)
