package io.mesazon.waha.domain.output

import io.mesazon.waha.domain.UserAccountID

case class GroupsUpdateOutput(
    nonRegisteredUserAccountIDs: List[UserAccountID]
)
