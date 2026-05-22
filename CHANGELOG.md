# Change Log

All notable changes to this project will be documented in this file. This
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
and the format of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased]

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

[Unreleased]: https://github.com/mbjarland/clj-zip-meta/compare/0.2.0...HEAD
[0.2.0]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.3...0.2.0
[0.1.3]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.2...0.1.3
[0.1.2]: https://github.com/mbjarland/clj-zip-meta/compare/0.1.0...0.1.2
[0.1.0]: https://github.com/mbjarland/clj-zip-meta/releases/tag/0.1.0
