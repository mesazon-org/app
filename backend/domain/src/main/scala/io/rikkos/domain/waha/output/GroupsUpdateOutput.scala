package io.rikkos.domain.waha.output

import io.rikkos.domain.waha.UserAccountID

case class GroupsUpdateOutput(
    nonRegisteredUserAccountIDs: List[UserAccountID]
)
