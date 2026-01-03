package io.mesazon.waha.it.utils

import io.mesazon.waha.domain.input.*
import io.rikkos.testkit.base.*
import org.scalacheck.*
import org.scalacheck.Arbitrary.arbitrary

trait WahaArbitraries extends IronRefinedTypeArbitraries, IronRefinedTypeTransformer {

  given Arbitrary[FileType] = Arbitrary(
    Gen.oneOf(Gen.resultOf(FileType.Data.apply), Gen.resultOf(FileType.Url.apply))
  )

  given Arbitrary[ChattingMessageInput.Text]  = Arbitrary(Gen.resultOf(ChattingMessageInput.Text.apply))
  given Arbitrary[ChattingMessageInput.Image] = Arbitrary(Gen.resultOf(ChattingMessageInput.Image.apply))
  given Arbitrary[ChattingMessageInput.File]  = Arbitrary(Gen.resultOf(ChattingMessageInput.File.apply))
  given Arbitrary[ChattingMessageInput.Voice] = Arbitrary(Gen.resultOf(ChattingMessageInput.Voice.apply))
  given Arbitrary[ChattingMessageInput.Video] = Arbitrary(Gen.resultOf(ChattingMessageInput.Video.apply))

  given Arbitrary[ChattingMessageInput] = Arbitrary(
    Gen.oneOf(
      arbitrary[ChattingMessageInput.Text],
      arbitrary[ChattingMessageInput.Image],
      arbitrary[ChattingMessageInput.File],
      arbitrary[ChattingMessageInput.Voice],
      arbitrary[ChattingMessageInput.Video],
    )
  )

  // Groups Inputs
  given Arbitrary[GroupsCreateInput] = Arbitrary(Gen.resultOf(GroupsCreateInput.apply))
  given Arbitrary[GroupsDeleteInput] = Arbitrary(Gen.resultOf(GroupsDeleteInput.apply))
  given Arbitrary[GroupsUpdateInput] = Arbitrary(Gen.resultOf(GroupsUpdateInput.apply))
}
