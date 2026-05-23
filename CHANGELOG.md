# Change Log

All notable changes to this project will be documented in this file. This
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and the format of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]

## [0.4.0] - 2026-05-23

### Added
- **Babashka compatibility.** The high-level API in
  `clj-zip-meta.core` and the CLI (`clj-zip-meta.cli`) now run under
  [Babashka](https://babashka.org/) — no JVM required. A `bb.edn` is
  bundled. Try `bb -m clj-zip-meta.cli list my.jar`.

### Changed (breaking)
- `read-spec-from-buffer`, `read-spec-from-file`,
  `write-spec-to-buffer!`, and `write-spec-to-file!` moved from
  `clj-zip-meta.core` to a new namespace `clj-zip-meta.spec-io`.
  These functions wrap Octet's `read` / `write!`, and Octet pulls in
  `io.netty.buffer`, which Babashka doesn't ship. Isolating them in
  their own namespace lets `clj-zip-meta.core` load anywhere.
  Migration: replace `(require '[clj-zip-meta.core :as zm])` calls
  to those four functions with `(require '[clj-zip-meta.spec-io :as
  sio])` / `(sio/...)`.
- The record signatures used internally are now byte-array constants
  in `clj-zip-meta.core` rather than maps re-exported from
  `clj-zip-meta.spec`. The maps in `clj-zip-meta.spec`
  (`rec-cdr-header-sig`, etc.) remain for backwards compatibility.
- `clj-zip-meta.core` no longer requires `clj-zip-meta.spec` or
  Octet at namespace-load time.

## [0.3.0] - 2026-05-22

### Performance
- **~150× faster reads.** The internal record reads no longer go
  through `octet`'s protocol-dispatch path — `zip-meta`, `zip-entries`,
  `zip-comment`, and `scan-local-headers` now use hand-rolled
  `ByteBuffer.getInt` / `.getShort` readers. On clojure-1.12.0.jar
  (3 770 entries):
  - `zip-meta` (full): 1 893 ms → 25 ms (with the new decoded keys)
                                / 12 ms (raw)
  - `zip-meta` (CDR only): 1 198 ms → 14 ms / 5 ms
  - `zip-entries`: 1 225 ms → 18 ms
- Hand-rolled writers replace `octet` on the repair path too
  (`repair-zip-with-preamble-bytes`, `rebuild-central-directory!`,
  `set-zip-comment!`). Octet remains as a dependency only for the
  legacy public `read-spec-from-*` / `write-spec-to-*` wrappers.
- A `bench/clj_zip_meta/bench.clj` namespace runs criterium
  quick-benches against the hot paths. Invoke via `lein bench` or
  `clj -M:bench`.

### Added
- **Decoded convenience keys** on every record (opt out with
  `{:decode false}`):
  - `:last-modified` — `java.time.LocalDateTime`.
  - `:dos-attributes` — set decoded from the low byte of
    `:external-file-attributes` (CDR only).
  - `:unix-mode` — Unix file mode (octal) from the upper 16 bits of
    `:external-file-attributes`, when `:version-made-by` indicates
    Unix host. `nil` otherwise.
  - `:directory?`, `:encrypted?`, `:utf8-name?` — booleans derived
    from `:general-purpose` and the file name.
  - `:extra-fields` — parsed TLV vector. Each entry has `:tag`,
    `:tag-name`, `:size`, `:data`. Tag `0x5455` (extended-timestamp)
    additionally carries a decoded `:decoded` map.
- **CRC-32 verification** of entry contents:
  - `verify-crcs` — read every entry's compressed data, decompress
    (STORED and DEFLATE), and compare against the recorded CRC-32.
  - `verify-crcs-summary` — folds the per-entry results into status
    counts plus mismatches / errors / aggregate `:valid?`.
  - `validate-zip-meta` accepts `:verify-crcs true` to roll CRC
    failures into its existing `:issues` vector.
- `find-entry` — look up the compact summary for one entry by name.
- `zip-meta` accepts `{:include-locals false}` to skip reading the
  per-entry local file headers.
- **`deps.edn`** manifest with `:test`, `:cli`, and `:bench` aliases
  for tools.deps users.
- **CLI** gains a `verify` command, a `--crc` flag on `validate`, a
  `--json` flag on every read-only command (with an inline JSON
  emitter — no new dependency), and a `--version` flag.

### Changed
- Bumped library version to 0.3.0.
- `dump-sig-number` now emits the on-disk little-endian byte order
  so the result actually matches what a hex dump shows.

## [0.2.0] - 2026-05-22

### Added
- High-level convenience API:
  - `zip-entries` — return a vector of entry summaries (name, size, offset).
  - `zip-comment` — read the archive comment from the end-of-CDR record.
  - `print-zip-meta` — pretty-print a meta map for inspection.
  - `summarize` — return a compact summary map (entry count, sizes, comment).
- Accepts a `String` path, `java.io.File`, or `java.nio.file.Path` as input
  for every public function.
- `repair-zip-with-preamble-bytes` now returns the file it operated on so
  it can be threaded.
- Optional `:repair` keyword argument on `validate-zip-meta` that fixes
  prepended-byte offset drift in place.
- Comprehensive `clojure.test` suite covering parsing, repair round-trip,
  write functions, edge cases, and input-type handling.
- GitHub Actions CI matrix over Clojure 1.10 / 1.11 / 1.12 on JDK 11/17/21.
- `CONTRIBUTING.md` and a real `doc/intro.md`.

### Changed
- Bumped Clojure from 1.8.0 to 1.12.0. Clojure 1.8 fails to compile on
  modern JDKs, so this also re-enables building on JDK 17 / 21.
- Replaced the Midje test suite with `clojure.test`. Midje is no longer a
  required dev dependency.
- `find-byte-pattern` rewritten with a 64 KiB sliding buffer instead of
  scanning one byte at a time; locating the end-of-CDR record on large
  archives is now dramatically faster.
- Resource handling tightened: file channels and random-access files are
  always closed via `with-open`.
- Better error messages: malformed archives raise `ex-info` with the
  offsets, signatures, and file name involved instead of a generic
  `IllegalStateException`.
- Reflection warnings eliminated (`*warn-on-reflection*` is now enabled by
  default in the `:dev` profile and the project compiles cleanly).

### Fixed
- `:dev` resource paths were declared at the top level instead of inside
  `:profiles`, so test resources were never on the dev classpath via the
  intended mechanism.
- `repair-zip-with-preamble-bytes` opened the file once per record;
  now it opens it once and writes through a single random-access file.
- License field said "Some Eclipse Public License" — corrected to
  "Eclipse Public License 1.0".

### Removed
- Stale template content in `CHANGELOG.md` and `doc/intro.md`.

## [0.1.3] - 2018

### Changed
- Bump `funcool/octet` to 1.1.2 (PR #2).

## [0.1.2] - 2017

### Added
- First publish to Clojars.

## [0.1.0] - 2017-09

### Added
- Initial implementation: parse end-of-CDR, central directory headers, and
  local file headers from zip/jar files; repair offsets after prepending
  preamble bytes.

[Unreleased]: https://github.com/mbjarland/clj-zip-meta/compare/0.4.0...HEAD
[0.4.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.3...0.2.0
[0.1.3]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.0...0.1.2
[0.1.0]: https://github.com/mbjarland/clj-zip-meta/releases/tag/0.1.0
