# Streaming upload byte handling

Read when changing `FileScanner.scan`, `ImageProcessing.normalize`, upload byte-cap enforcement, or their interaction with `EntityLimiter`. Companion to [Alternate HTTP](alternate-http.md) (transport/entity-limit wiring) and [Known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast) (open upstream hang).

## `EntityLimiter` is not a hard cap

`org.http4s.server.middleware.EntityLimiter` wraps the Tapir routes (`HttpApp.scala`, `TapirMaxEntitySize` = `file-service.max-upload-bytes` = 20 MB, kept in sync manually — not enforced structurally). It does not truncate the body. Its `takeLimited(n)`: take `n` bytes; if more remain, echo **all** the remainder to the downstream reader (unbounded — as much as the client sends), then raise `EntityTooLarge` only once that remainder hits real EOF. It guarantees eventual failure, never a bounded amount of data delivered before failure.

Consequences for `FileScanner.scan`:

- Cannot stop reading once its own cap is hit. Stopping early (old: `.take(cap+1).run(sink)`) leaves `EntityLimiter`'s echoed remainder undrained on the connection — the socket is never fully read, which can stall it. (Necessary, not proven sufficient: draining fully did not fix the reproduced hang in known issues — root cause is upstream, in Ember/Tapir interop.)
- Cannot write every received byte and check size after. `EntityLimiter` echoes an arbitrarily large body before failing, so "write everything, check after" is an unbounded disk write.

## Current design

`FileScanner.scan` folds `fileByteStream.chunks` in one pass (`runFoldZIO`): pulls to true EOF (drains the connection fully) but writes at most `maxFileBytes + 1` bytes to the temp file. Bytes beyond the cap are counted, never buffered or written — disk usage stays bounded regardless of actual body size, while the connection still gets fully drained.

Fold over `.chunks` (`Chunk[Byte]`), not the raw `Byte` stream (`mapZIO`/`runForeach` per element): each element on ZIO's effect interpreter costs a suspension. Chunking keeps blocking I/O (`ZIO.attemptBlocking`) and array writes at one call per chunk (~KBs), not one per byte.

## `ImageProcessing` doesn't need this

`ImageProcessing.normalize` writes its input via plain `ZSink.fromPath` — no custom fold. `ZSink.fromPath` is itself `ZSink.foldLeftChunksZIO` internally (zio-streams `platform.scala`): already one blocking write per chunk. Its input is `FileByteStreamScanned`, i.e. `FileScanner`'s already-scanned, already-capped output on local disk, not the untrusted network body — no truncate-while-draining logic needed.

## Alternative not taken

A custom http4s middleware (`Pipe[F, Byte, Byte]`) could truncate at `n` bytes and internally `rest.compile.drain` the excess instead of echoing it — centralizing "drain the socket" in one middleware instead of every streaming consumer, letting `FileScanner` go back to a plain `.take(n).run(sink)`. Not implemented; not tested against the open known-issues hang.
