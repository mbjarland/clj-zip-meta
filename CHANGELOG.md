# Change Log

All notable changes to this project will be documented in this file. This
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and the format of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]

## [0.5.1] - 2026-05-23

### Changed
- **CLI output rewrite.** Every command got a fresh, consistent
  layout: bold title row (`command  filename`), 2-space-indented
  body, aligned key/value blocks with right-aligned numeric
  columns, dim section subheaders, and a coloured `OK` / `FAILED`
  status line at the end where applicable.
  - Numeric values use comma-thousands separators
    (`3,770`, not `3770`).
  - Tables use light box-drawing rules instead of dashed `------`.
  - `print-layout` reformats with right-aligned numbers and tight
    column widths.
  - ANSI colour codes only print when stdout is a real TTY and
    neither `--json` nor `--no-color` is set.
- `classes`: default view is a `pkg -> count` summary; pass
  `--all` for the full per-package listing.
- `grep`: exits 1 when no entry matches (matches POSIX `grep`
  convention; lets shell scripts react).

### Added
- `--no-color` global flag.

## [0.5.0] - 2026-05-23

### Added — entry inspection
- `extract-bytes f entry-name` — extract one entry's uncompressed
  data in memory. Supports STORED + DEFLATE. The library still does
  not write archives; this is for inspection only.
- `extract-string f entry-name [charset]` — UTF-8 (or any) decode.

### Added — Java / JAR understanding
- `manifest f` — parse `META-INF/MANIFEST.MF` into a map of header
  name to value. Handles CRLF / CR / LF and the space-prefixed
  continuation-line convention.
- `manifest-sections f` — `{:main … :entries [{…} …]}` so per-entry
  attribute sections (signed jars, etc.) are addressable too.
- `jar-info f` — distil the manifest to the fields that actually
  matter: `:main-class`, `:implementation-version`, OSGi
  `:bundle-symbolic-name`, Java agent `:premain-class`, etc.
- `pom-info f` — parse
  `META-INF/maven/{group}/{artifact}/pom.properties` into
  `{:group-id :artifact-id :version}`.
- `class-index f` — sorted map of Java package → sorted vector of
  class names. The default package is keyed under `""`.
- `describe f` — one-shot "what is this jar?" summary combining
  `summarize`, `jar-info`, `pom-info`, class/resource counts, and
  the top-level directories.

### Added — recursive forensics
- `clj-zip-meta.analysis/analyze-nested` — for each entry that
  looks like a nested archive (`.jar`, `.zip`, `.war`, `.ear`),
  extract it to a temp file and recursively run `analyze`.
  Returns a tree of `{:entry-name :analysis :nested}` maps. Useful
  for Spring Boot fat jars, uberjars-of-uberjars, and any payload
  that ships archives as entries.

### Added — classpath investigation
- `spi-providers` — parse `META-INF/services/*` and return a
  sorted map of `service-interface -> [impl-class …]`. Tells you
  what services a jar contributes to a JVM without loading it.
- `duplicate-classes` — given a collection of jar paths, find
  class names declared by more than one jar. The most common
  cause of mysterious `LinkageError` / `NoSuchMethodError` at
  runtime is two jars on the classpath that define different
  versions of the same class.

### Added — CLI
- `manifest FILE`         — pretty-print the manifest.
- `jar-info FILE`         — pretty-print distilled jar fields.
- `describe FILE`         — single-shot jar summary.
- `classes  FILE`         — class index grouped by package.
- `spi      FILE`         — `META-INF/services/*` providers.
- `duplicate-classes FILE FILE …` — find shared class names across
  jars (exits 2 if any duplicate found).
- `cat FILE ENTRY`        — write one entry to stdout.
- `analyze FILE --recursive` — walk nested archives.

## [0.4.0] - 2026-05-23

### Added
- **Babashka compatibility.** The high-level API in
  `clj-zip-meta.core` and the CLI (`clj-zip-meta.cli`) now run under
  [Babashka](https://babashka.org/) — no JVM required. A `bb.edn` is
  bundled. Try `bb -m clj-zip-meta.cli list my.jar`.
- `zip-entries` accepts an options map with `:match`, which keeps
  only entries whose `:file-name` matches a regex `Pattern`, a
  substring `String`, or a predicate function. CLI `list FILE
  --match PAT` exposes this.
- `diff a b` — compare two archives by file-name and report which
  entries are added, removed, or changed (CRC / size differences),
  plus a count of identical entries. Exposed as `diff FILE-A FILE-B`
  on the CLI; exits 2 if any difference is found.
- `hexdump f offset [length]` — classic hex-dump view of `length`
  bytes (default 256) starting at byte offset `offset`. Useful when
  staring at a specific record's raw bytes during a repair
  investigation. Exposed as `hexdump FILE OFFSET [LENGTH]` on the
  CLI.
- `update-cdr-entries! f updater` — rewrite every CDR record with a
  function. Returning `nil` from the updater drops the entry from
  the central directory.
- `set-entry-comment! f file-name comment` — set a single entry's
  comment.
- `zero-timestamps! f` — clear `:last-mod-file-time` and
  `:last-mod-file-date` on every CDR record (a step toward
  reproducible-build archives).
- `:symlink?` convenience boolean on CDR records (Unix file-type
  bits == 0o120000).
- NTFS extra-field decoder (tag 0x000A): surfaces `:mtime`,
  `:atime`, `:ctime` as UTC LocalDateTime values when present.
- PKWARE Unix extra-field decoder (tag 0x000D): surfaces `:atime`,
  `:mtime`, `:uid`, `:gid`.
- `doc/cljdoc.edn` so [cljdoc.org](https://cljdoc.org/) renders the
  README, intro, changelog, and contributor guide as navigation
  entries on the published docs site.

### Analytical helpers (core)
- `largest`, `smallest`, `newest`, `oldest` — top-N entries ranked
  by size or modification time.
- `group-by-dir` — entries grouped by their parent directory.
- `compression-stats` — overall, per-method, per-extension, and
  worst-ratio compression statistics.

### Layout visualization (core)
- `layout f` — returns a vector describing every record's physical
  byte range (`:preamble`, `:lfh`, `:data`, `:data-descriptor`,
  `:cdr`, `:eocdr`, `:gap`).
- `print-layout f {:width N}` — pretty-prints the regions as a
  table, with an optional `N`-wide ASCII byte-map showing where
  each region lives in the file:

      |PPPPPPPPPPPPPPLLLLLLLLLLDDDDCCCCCCCCCCCC|  22 bytes/char
        P=preamble L=LFH D=data d=descriptor C=CDR E=EOCDR -=gap

### Forensics — new `clj-zip-meta.analysis` namespace
- `unsafe-entries` — paths that would escape the extract directory
  (zip-slip), have null bytes, absolute paths, Windows reserved
  names, etc.
- `zip-bomb-risk` — entries with extreme uncompressed-to-compressed
  ratios.
- `gap-data` — byte ranges not claimed by any record. A classic
  hiding place for piggy-backed content.
- `cdr-lfh-mismatches` — entries whose CDR and LFH disagree on
  name, size, or CRC. A known vector for tool-confusion attacks.
- `zip64?` — true when the EOCDR uses Zip64 sentinel values.
  This library does not yet read the Zip64 records.
- `analyze f` — runs all the above and returns a single safety
  report with a `:safe?` flag.

### CLI: tree, inspect, grep, layout, analyze
- `tree FILE [--match PAT]` — show entries as a directory tree.
- `inspect FILE ENTRY-NAME` — pretty-print everything we know
  about one entry.
- `grep FILE PATTERN` — list entry names matching a regex.
- `layout FILE [--width N]` — pretty-print the physical layout
  with an optional byte-map visualization.
- `analyze FILE` — run the safety / forensics suite (exits 1
  if the archive is not `:safe?`).

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

[Unreleased]: https://github.com/mbjarland/clj-zip-meta/compare/0.5.1...HEAD
[0.5.1]: https://github.com/mbjarland/clj-zip-meta/compare/0.5.0...0.5.1
[0.5.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.4.0...0.5.0
[0.4.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.3...0.2.0
[0.1.3]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.0...0.1.2
[0.1.0]: https://github.com/mbjarland/clj-zip-meta/releases/tag/0.1.0
