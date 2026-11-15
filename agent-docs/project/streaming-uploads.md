# Streaming upload byte handling

Read when changing `FileScanner.scan`, `ImageProcessing.normalize`, upload byte-cap enforcement, or their interaction with `EntityLimiter`. Companion to [Alternate HTTP](alternate-http.md) (transport/entity-limit wiring) and [Known issues](../known-issues.md#oversized-tapir-upload-can-hang-the-request-instead-of-failing-fast) (open upstream hang).

## `EntityLimiter` is not a hard cap

`org.http4s.server.middleware.EntityLimiter` wraps the Tapir routes (`HttpApp.scala`, `TapirMaxEntitySize` = 20 MiB, manually aligned with `file-service.file-bytes-max`, both `catalogue-file-service` endpoint-specific defaults, and `customer-book-file-service.extract-customers-file-bytes-max` — not enforced structurally). It does not truncate the body. Its `takeLimited(n)`: take `n` bytes; if more remain, echo **all** the remainder to the downstream reader (unbounded — as much as the client sends), then raise `EntityTooLarge` only once that remainder hits real EOF. It guarantees eventual failure, never a bounded amount of data delivered before failure.

Consequences for `FileScanner.scan`:

- Cannot stop reading once its own cap is hit. Stopping early (old: `.take(cap+1).run(sink)`) leaves `EntityLimiter`'s echoed remainder undrained on the connection — the socket is never fully read, which can stall it. (Necessary, not proven sufficient: draining fully did not fix the reproduced hang in known issues — root cause is upstream, in Ember/Tapir interop.)
- Cannot write every received byte and check size after. `EntityLimiter` echoes an arbitrarily large body before failing, so "write everything, check after" is an unbounded disk write.

## Current design

`FileScanner.scan` folds `fileByteStream.chunks` in one pass (`runFoldZIO`): pulls to true EOF (drains the connection fully) but writes at most `fileBytesMax + 1` bytes to the temp file. Bytes beyond the cap are counted, never buffered or written — disk usage stays bounded regardless of actual body size, while the connection still gets fully drained.

`scan` requires the declared filename, validates its extension against the caller's allowed media types before reading the body, writes to a scoped temp file, asks Tika to detect the content using that filename as its hint, and rejects a declared/detected mismatch with `BadRequest`. It returns `FileScannerScanOutput`: the scoped `FileScannedPath`, matched `SupportedMediaType`, and actual `FileBytesSize`. `FileService`, `CatalogueFileService` and `CustomerBookFileService` use this method. Catalogue and Customer Book extraction pass the path directly to the AI or spreadsheet client without storing or normalizing the source; logo and catalogue image uploads rebuild a `FileByteStreamScanned` from the already capped local path for `ImageProcessing`.

`SupportedMediaType.PDF` (`pdf`, `application/pdf`) belongs to the extraction list `aiExtractData = images ++ pdfs ++ spreadsheets`, scanned by both Customer Book and Catalogue extraction (Customer Book PDF round, user decision; `extractCatalogueData` was removed). Image uploads keep `images`, so they still refuse `.pdf` (see [Customer Book PDF extraction](../features/customer-book.md#pdf-extraction)). A PDF is checked like every other type: one `tika.detect(inputStream, fileNameDeclared)` call, whose result must be `application/pdf`. `FileScanner` has no PDF branch and no fallback of its own. When Tika recognises the content as another type, that type is returned and the scan fails as a mismatch (PNG bytes named `.pdf` are a `BadRequest`). When Tika's own content detection finds no specific type, Tika uses the name hint and returns `application/pdf`. This was observed (issue 607 PDF round, not kept as a test) for binary bytes with no signature Tika recognises, such as `00 01 02 03 FE FF`: named `.pdf`, they pass the scan and, for either extraction feature, are sent to the AI, where a provider rejection gives `500`. Text-like bytes may instead be detected as a text type and fail as a mismatch; that case is not pinned by a test (see [Catalogue PDF extraction](../features/catalogue.md#pdf-extraction)). Because the call is the same for every type, the same name fallback is expected, but not verified, for unidentifiable bytes named `.png`, `.jpg`, `.jpeg` or `.webp`: they would pass the scan and fail later in the AI call or image decoding rather than with a `400` mismatch. This is recorded as known behaviour, not as a planned change; making the scanner stricter would be a code change that needs the user's decision. Customer Book and Catalogue `aiExtract` both scan with `aiExtractData` (issue 607).

Fold over `.chunks` (`Chunk[Byte]`), not the raw `Byte` stream (`mapZIO`/`runForeach` per element): each element on ZIO's effect interpreter costs a suspension. Chunking keeps blocking I/O (`ZIO.attemptBlocking`) and array writes at one call per chunk (~KBs), not one per byte.

## `ImageProcessing` doesn't need this

`ImageProcessing.normalize` writes its input via plain `ZSink.fromPath` — no custom fold. `ZSink.fromPath` is itself `ZSink.foldLeftChunksZIO` internally (zio-streams `platform.scala`): already one blocking write per chunk. Its input is `FileByteStreamScanned`, i.e. `FileScanner`'s already-scanned, already-capped output on local disk, not the untrusted network body — no truncate-while-draining logic needed.

## Alternative not taken

A custom http4s middleware (`Pipe[F, Byte, Byte]`) could truncate at `n` bytes and internally `rest.compile.drain` the excess instead of echoing it — centralizing "drain the socket" in one middleware instead of every streaming consumer, letting `FileScanner` go back to a plain `.take(n).run(sink)`. Not implemented; not tested against the open known-issues hang.
