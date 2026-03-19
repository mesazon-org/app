package io.mesazon.domain.waha.output

import io.mesazon.domain.waha.UserAccountID

case class GroupsUpdateOutput(
    nonRegisteredUserAccountIDs: List[UserAccountID]
)
