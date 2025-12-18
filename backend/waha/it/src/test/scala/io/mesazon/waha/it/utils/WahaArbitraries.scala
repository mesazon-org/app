package io.mesazon.waha.it.utils

import io.mesazon.waha.domain.input.*
import io.rikkos.testkit.base.*
import org.scalacheck.*

trait WahaArbitraries extends IronRefinedTypeArbitraries, IronRefinedTypeTransformer {

  given Arbitrary[FileType] = Arbitrary(
    Gen.oneOf(Gen.resultOf(FileType.Data.apply), Gen.resultOf(FileType.Url.apply))
  )

  // Chatting Inputs
  given Arbitrary[ChattingMessageInput.Text] = Arbitrary(Gen.resultOf(ChattingMessageInput.Text.apply))

  // Groups Inputs
  given Arbitrary[GroupsCreateInput] = Arbitrary(Gen.resultOf(GroupsCreateInput.apply))
  given Arbitrary[GroupsDeleteInput] = Arbitrary(Gen.resultOf(GroupsDeleteInput.apply))
  given Arbitrary[GroupsUpdateInput] = Arbitrary(Gen.resultOf(GroupsUpdateInput.apply))
}
