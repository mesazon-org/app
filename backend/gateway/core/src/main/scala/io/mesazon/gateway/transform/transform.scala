package io.mesazon.gateway.transform

import io.scalaland.chimney.Transformer
import smithy4s.Nullable
import zio.NonEmptyChunk

given nonEmptyChunkToListTransformer[Source, Target](using
    transformer: Transformer.AutoDerived[Source, Target]
): Transformer[NonEmptyChunk[Source], List[Target]] =
  nonEmptyChunk => nonEmptyChunk.toList.map(transformer.transform)

given optionNullableTransformer[Source, Target](using
    transformer: Transformer[Source, Target]
): Transformer[Option[Source], Nullable[Target]] =
  value => Nullable.fromOption(value.map(transformer.transform))
