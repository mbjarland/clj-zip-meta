# Release notes

A narrative companion to [CHANGELOG.md](../CHANGELOG.md). The
changelog is _what changed_; this is _what happened, and why_.

## TL;DR

Between **0.1.3** (the last release, 2018) and **0.5.2** (this
round) `clj-zip-meta` went from a working-but-rough proof of concept
to a production-grade library for zip / jar metadata.

|                                          | 0.1.3       | 0.5.2          |
| ---------------------------------------- | ----------- | -------------- |
| Clojure version                          | 1.8         | 1.10–1.12      |
| Compiles on JDK 17 / 21                  | no          | yes            |
| Test count                               | 5           | 70 / 234 asserts |
| Namespaces                               | 2           | 5              |
| CLI commands                             | 0           | 23             |
| `zip-meta` on a 3 770-entry / 4 MB jar  | ~1 893 ms   | ~12 ms (≈ 150×) |
| Babashka-compatible                      | no          | yes (core + CLI) |
| Forensics suite (zip-slip / zip-bomb / …)| no          | yes            |
| Java / JAR understanding (manifest, POM, classes, SPI) | no | yes |
| Layout visualisation                     | no          | yes            |
| Continuous integration                   | no          | matrix (3×3) green |
| Release workflow to Clojars              | no          | tag-driven     |

## The journey

The work happened in six focused rounds, each shipped as a tagged
release.

### 0.2.0 — Modernise and add deep repair

The starting state: Clojure 1.8.0, didn't compile on JDK 17+, Midje
tests, no CI, `CHANGELOG.md` still carrying the Leiningen-template
"widget" placeholder text, and a typo in the `:license` field.

What landed:

- Clojure bumped to 1.12, profile matrix for 1.10 / 1.11 / 1.12.
- Byte-by-byte EOCDR scan replaced with a 64 KiB sliding window.
- Single mmap per `zip-meta`; fixed a subtle bug where closing a
  FileChannel also closed a caller-supplied `RandomAccessFile`.
- `ex-info` with structured data on malformed archives instead of
  a generic `IllegalStateException`.
- Convenience API: `zip-entries`, `zip-comment` / `set-zip-comment!`,
  `find-entry`, `summarize`, `print-zip-meta`.
- **Deep repair** —
  `scan-local-headers` walks LFH signatures forward and handles
  data descriptors (with and without the optional `0x08074b50`
  signature); `rebuild-central-directory!` reconstructs a missing
  or corrupt CDR from local headers; `strip-preamble!` physically
  removes prepended bytes; `repair-zip` is a multi-strategy
  orchestrator.
- The 23-command CLI started here.
- Midje swapped for `clojure.test`. 34 tests / 100 assertions.
- GitHub Actions matrix CI + tag-driven release workflow.

### 0.3.0 — Performance + decoded keys + CRC verification

The baseline measurement was telling: `zip-meta` on
`clojure-1.12.0.jar` (3 770 entries, 4 MB) took **1.9 seconds** —
almost all of it inside Octet's protocol dispatch on each field of
each record. Hand-rolled `ByteBuffer.getInt` / `.getShort` readers
replaced the internal path:

| metric                  | before   | after  | speedup |
| ----------------------- | -------- | ------ | ------- |
| `zip-meta` (full)       | 1 893 ms | 12 ms  | **151×** |
| `zip-meta` (CDR-only)   | 1 198 ms |  5 ms  | **179×** |
| `zip-entries`           | 1 225 ms |  7 ms  | **170×** |
| `find-end-of-cdr-offset`|     —    | 16 µs  |    —     |
| `verify-crcs` (NEW)     |     —    | 40 ms  |    —     |

Every CDR record now carries decoded convenience keys:
`:last-modified` (`LocalDateTime`), `:dos-attributes` (set),
`:unix-mode` (octal), `:directory?`, `:encrypted?`, `:utf8-name?`,
`:symlink?`, `:extra-fields` (parsed TLV vector). Opt out with
`{:decode false}`.

`verify-crcs` reads each entry's compressed data, decompresses, and
compares against the recorded CRC-32 — supports STORED and DEFLATE.

A `deps.edn` manifest with `:test`, `:cli`, `:bench` aliases, plus a
criterium quick-bench suite under `bench/`. CLI gained `--json` and
`--version`. JSON emitter inlined (no new dependency).

### 0.4.0 — Babashka + jar understanding

To get the library running under [Babashka](https://babashka.org/),
the main `clj-zip-meta.core` namespace had to be Octet-free at
load time (Octet pulls in `io.netty.buffer`, which Babashka doesn't
ship). Hand-rolled the writers as well, then moved the four legacy
spec-IO wrappers (`read-spec-from-*` / `write-spec-to-*`) to a new
`clj-zip-meta.spec-io` namespace. Bundled a `bb.edn`. The CLI and
the high-level API now run end-to-end under bb.

The library also expanded from _metadata about archives_ to
_metadata about the archive's contents_:

- `extract-bytes` / `extract-string` — one-entry data extraction.
- `manifest`, `manifest-sections`, `jar-info` — parse
  `META-INF/MANIFEST.MF`. Handles CRLF / CR / LF and the
  space-prefixed continuation-line convention.
- `pom-info` — `META-INF/maven/.../pom.properties` →
  `{:group-id :artifact-id :version}`.
- `class-index` — sorted package → sorted class names.
- `describe` — one-shot "what is this jar?" summary.

CLI gained `list --match`, `tree`, `diff`, `hexdump`. Library
gained `update-cdr-entries!`, `set-entry-comment!`,
`zero-timestamps!` (a reproducible-build helper).

### 0.5.0 — Forensics + creative extras

A coherent _is this archive safe?_ suite in a new
`clj-zip-meta.analysis` namespace:

- `unsafe-entries` — zip-slip / null bytes / absolute paths /
  Windows reserved names / control characters.
- `zip-bomb-risk` — entries with extreme uncompressed-to-compressed
  ratios.
- `gap-data` — byte ranges no record claims (classic hiding place
  for piggy-backed content).
- `cdr-lfh-mismatches` — CDR vs LFH disagreement (known
  tool-confusion attack vector).
- `zip64?` — sentinel detection (this library doesn't parse Zip64
  records).
- `analyze` — bundles everything into a single `:safe?` report.
- `analyze-nested` — recursively walks `.jar` / `.zip` / `.war` /
  `.ear` entries. Spring Boot fat jars, uberjars-of-uberjars,
  payloads that ship archives as entries.

Layout visualisation:

```
|PPPPPPPPPPPPPPPPPPPPPLLLLLLLLLLLLLLDDDDDDCCCCCCCCCCCCCCCCCEE|  15 bytes/char
  P=preamble L=LFH D=data d=descriptor C=CDR E=EOCDR -=gap
```

Classpath investigation:

- `spi-providers` — parse `META-INF/services/*` so you can see what
  a jar wires into the JVM without loading it.
- `duplicate-classes` — across a set of jars, find class names
  declared by more than one. Catches the most common cause of
  mysterious `LinkageError` at runtime.

Analytical helpers: `largest`, `smallest`, `newest`, `oldest`,
`group-by-dir`, `compression-stats`. NTFS, PKWARE-Unix, and
extended-timestamp extra-field decoders.

### 0.5.1 — TUI polish

Inconsistent ad-hoc `printlns` rewritten into a small set of
helpers (`title`, `subtitle`, `kv-block`, `print-table`,
`status-line`). Numeric formatting with comma-thousands separators
(`3,770` not `3770`). Light box-drawing rules instead of dashes.
ANSI colour when stdout is a TTY (with a `--no-color` escape hatch).

### 0.5.2 — Alignment, after a full audit

A user spotted that `verify` output was misaligned in colour mode.
Root cause: Java `format`'s `%-Ns` padding counts ANSI escape codes
as visible width, so a coloured `"ok"` (eleven raw chars, two
visible) ended up wider than a plain `"empty"` (five raw, five
visible). Wrote visible-length-aware `pad-left` / `pad-right`
primitives and routed every column-aligned spot through them.

The audit caught four more bugs along the way:

- `validate --crc` threw a `ClassCastException` because the
  `:verify-crcs` keyword option shadowed the `verify-crcs` function
  the body then tried to call.
- `analyze --recursive` reported `SAFE` even when a nested archive
  carried unsafe entries.
- `inspect` silently dropped nullable fields like `:unix-mode` on
  Windows-made jars.
- `diff` had a hard-coded `%14s` size column with no ANSI awareness
  and no respect for the actual data widths.

All fixed. 70 tests / 234 assertions.

## The library today

### Five namespaces

| Namespace                  | What it does |
| -------------------------- | ------------ |
| `clj-zip-meta.core`        | Read, decode, mutate, repair. Hand-rolled fast path. Babashka-compatible. |
| `clj-zip-meta.analysis`    | Forensics / safety / recursive analysis. |
| `clj-zip-meta.cli`         | 23-command CLI with colour + JSON. |
| `clj-zip-meta.spec`        | Octet record specs. Unchanged for backwards compat. |
| `clj-zip-meta.spec-io`     | Octet-backed read/write wrappers, isolated so `core` can load without Octet. |

### CLI

| Category            | Commands |
| ------------------- | -------- |
| Read & explore      | `list` · `tree` · `grep` · `inspect` · `cat` · `meta` · `summary` · `layout` · `hexdump` |
| Jar understanding   | `describe` · `manifest` · `jar-info` · `classes` · `spi` · `duplicate-classes` |
| Integrity & safety  | `validate` · `verify` · `analyze` · `diff` |
| Modify              | `comment` · `repair` |

Plus `--json` on every read command, `--no-color`, `--version`, and
`CLICOLOR_FORCE=1` for forcing colour through a pipe.

### Test matrix

- Clojure 1.10 / 1.11 / 1.12
- JDK 11 / 17 / 21
- 70 tests · 234 assertions
- Babashka-compatible (high-level API + CLI)

### Released to Clojars

All 0.5.x versions are live. To use the latest:

```clojure
;; Leiningen / Boot
[clj-zip-meta/clj-zip-meta "0.5.2"]

;; deps.edn
clj-zip-meta/clj-zip-meta {:mvn/version "0.5.2"}

;; Babashka
{:deps {clj-zip-meta/clj-zip-meta
        {:git/url "https://github.com/mbjarland/clj-zip-meta"
         :git/tag "v0.5.2"}}}
```

## What's still possible

Not done in this round, but feasible follow-ups:

- **Zip64 support** — archives >4 GB or >65 535 entries. Current
  code detects sentinel values but does not parse the Zip64
  records.
- **Streaming reads beyond the 2 GB mmap limit** — every public
  function memory-maps the file or a region of it. For multi-GB
  archives a chunked-read path would be needed.
- **GraalVM native-image config** — sub-millisecond CLI startup,
  shippable as a single static binary.
- **Property-based tests with `test.check`** — round-trip the
  hand-rolled reader/writer against generated CDR/LFH/EOCDR shapes
  to catch edge cases the integration tests miss.
- **AES encryption header decoder** — surface key/strength/method
  for AES-encrypted entries.
- **More extra-field decoders** — InfoZIP UTF-8 path/comment, JAR
  module info, OSGi attributes embedded in the manifest.
- **Recursive `extract-bytes`** — extract a file from a nested
  archive in one call (`some.jar!/inner.jar!/path/in/inner.txt`).
- **HTML report** — single self-contained HTML file rendering
  `describe` + `analyze` + layout + classes + spi.
