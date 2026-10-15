package io.mesazon.gateway.transform

import io.scalaland.chimney.Transformer
import zio.NonEmptyChunk

given nonEmptyChunkToListTransformer[Source, Target](using
    transformer: Transformer.AutoDerived[Source, Target]
): Transformer[NonEmptyChunk[Source], List[Target]] =
  nonEmptyChunk => nonEmptyChunk.toList.map(transformer.transform)
