# clj-zip-meta

[![CI](https://github.com/mbjarland/clj-zip-meta/actions/workflows/ci.yml/badge.svg)](https://github.com/mbjarland/clj-zip-meta/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/clj-zip-meta.svg)](https://clojars.org/clj-zip-meta)
[![License](https://img.shields.io/badge/License-EPL%201.0-green.svg)](https://opensource.org/licenses/EPL-1.0)

A Clojure library for reading and patching the binary **metadata** of zip
and jar files — local file headers, central directory headers, and the
end-of-central-directory record, as defined by the
[PKWARE zip specification][zipspec]. It does **not** extract entry
data; for that, use `java.util.zip.ZipFile` or libraries built on it.

[zipspec]: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT

## What it gives you

* `zip-meta` — read every record in an archive and return it as a
  plain Clojure map.
* `zip-entries` — fast, CDR-only entry listing.
* `find-entry` — look up an entry by name.
* `zip-comment` / `set-zip-comment!` — read or rewrite the archive
  comment.
* `summarize` — high-level statistics in a single map.
* `validate-zip-meta` — check the metadata for self-consistency.
* `repair-zip` — fix prepended-byte offset drift, strip preambles,
  or **rebuild a missing central directory from local file headers**.
* A `lein run` CLI for the same operations from the shell.

## Why?

`clj-zip-meta` reads files the way the zip spec intends: it scans
backwards from the end of the file for the end-of-central-directory
record, then uses the offsets recorded there to locate the central
directory. That makes it tolerant of archives that have arbitrary bytes
prepended (self-extracting zips, executable jars with a shell prelude,
etc.) — situations where many naive zip readers give up.

It was originally written to support `cli-cmd`, a Leiningen template
that builds executable Clojure jars: a small shell prelude is prepended
to the jar so it can be `chmod +x`-ed and run directly. Prepending
bytes without updating the recorded offsets breaks the archive for many
zip tools; `clj-zip-meta` repairs it.

## Installation

Leiningen / Boot:

```clojure
[clj-zip-meta/clj-zip-meta "0.2.0"]
```

deps.edn:

```clojure
clj-zip-meta/clj-zip-meta {:mvn/version "0.2.0"}
```

Requires Clojure 1.10+ and JDK 11+.

## Library usage

```clojure
(require '[clj-zip-meta.core :as z])
```

### Listing entries

```clojure
(z/zip-entries "my.jar")
;;=> [{:file-name "META-INF/MANIFEST.MF"
;;     :file-comment ""
;;     :compressed-size 125
;;     :uncompressed-size 167
;;     :crc-32 -2096663765
;;     :compression-method 8
;;     :offset 2286}
;;    ...]

(z/find-entry "my.jar" "META-INF/MANIFEST.MF")
;;=> {:file-name "META-INF/MANIFEST.MF" ...}

(z/summarize "my.jar")
;;=> {:entry-count 10
;;    :extra-bytes 0
;;    :total-compressed 1842
;;    :total-uncompressed 5421
;;    :zip-comment ""}
```

### Full metadata

```clojure
(z/zip-meta "my.jar")
;;=> {:extra-bytes 0
;;    :end-of-cdr-record {:offset 2956
;;                        :record {:end-of-cdr-signature 101010256
;;                                 :number-of-this-disk 0
;;                                 ...}}
;;    :cdr-records   [{:offset 2231 :record {...}} ...]
;;    :local-records [{:offset 0    :record {...}} ...]}
```

The keys map one-to-one to the field names in APPNOTE.TXT §4.3.

When you only need the central directory, pass `{:include-locals
false}` to skip reading the per-entry local file headers. It is
noticeably faster on large jars:

```clojure
(z/zip-meta "big.jar" {:include-locals false})
```

### Pretty-printing

`print-zip-meta` renders the meta map with byte-array fields
(`:extra-field`, etc.) shown as space-separated hex instead of
`[B@xxxx` identity strings:

```clojure
(z/print-zip-meta "my.jar")
```

### Archive comment

```clojure
(z/zip-comment "my.jar")
;;=> ""

(z/set-zip-comment! "my.jar" "built by my-tool 1.4.2")
;;=> "my.jar"
```

### Validation

```clojure
(z/validate-zip-meta "my.jar")
;;=> {:valid? true, :issues [], :extra-bytes 0}

(z/validate-zip-meta "broken.jar")
;;=> {:valid? false
;;    :issues ["317 extra bytes at beginning or within zipfile"]
;;    :extra-bytes 317}
```

### Repair

Three repair strategies are available, each appropriate for a different
kind of breakage:

| Function | When the archive is broken because… |
| --- | --- |
| `repair-zip-with-preamble-bytes` | …bytes were prepended before the zip data without updating the recorded offsets. |
| `strip-preamble!` | …same as above, but you want the file to physically shrink rather than have offsets bumped. |
| `rebuild-central-directory!` | …the central directory or end-of-CDR record is missing or corrupt. Reconstructs it from the local file headers. |
| `repair-zip` | A top-level multi-strategy repair that picks whichever applies. |

```clojure
(z/repair-zip "broken.jar")
;;=> {:status :ok
;;    :actions [:preamble-fix]
;;    :before  {...the metadata before repair...}
;;    :after   {...the metadata after repair...}}

(z/repair-zip "truncated.jar")
;;=> {:status :ok
;;    :actions [:cdr-rebuilt]
;;    :before  nil           ;; archive did not parse before
;;    :after   {...}}

(z/repair-zip "with-shell-prelude.jar" {:strip-preamble true})
;;=> {:status :ok
;;    :actions [:preamble-stripped]
;;    ...}
```

#### What `rebuild-central-directory!` can recover

It walks the archive forward from the prepended-byte offset, reads each
local file header, and skips past its data. For entries that use a
trailing data descriptor (general-purpose bit 3 — the format Java's
default `ZipOutputStream` uses), the scan locates the descriptor,
validates that the recorded `compressed-size` matches the actual data
length, and patches the missing CRC / size fields back into the
rebuilt CDR entry. Both with-signature (`0x08074b50`) and
without-signature descriptor variants are supported.

If existing CDR records are partially readable, attributes that don't
live in the local file header — `:version-made-by`,
`:external-file-attributes`, `:internal-file-attributes`,
`:file-comment` — are preserved by name.

Limitations:

* No Zip64 support. Archives whose total size or per-entry size
  exceeds 4 GiB will not round-trip.
* Encrypted entries are read as-is; the library does not decrypt.
* Spanned (multi-disk) archives are not supported.

## Command-line usage

The library doubles as a CLI through `lein run`:

```
$ lein run -- list my.jar
    compressed   uncompressed name
    ----------   ------------ ----
             0              0 META-INF/
           125            167 META-INF/MANIFEST.MF
           412           1024 my/Foo.class
$ lein run -- summary my.jar
$ lein run -- validate my.jar
$ lein run -- repair broken.jar
$ lein run -- repair with-prelude.jar --strip
$ lein run -- comment my.jar
$ lein run -- comment my.jar "new archive comment"
```

`lein uberjar` produces a self-contained `clj-zip-meta-<version>-standalone.jar`
you can ship to a server and invoke with `java -jar`.

## Detail: the zip data model

A zip / jar file has the following overall structure:

```
  [ local file header 1     ]
  [ encryption header 1     ]   (optional)
  [ file data 1             ]
  [ data descriptor 1       ]   (optional)
  ...
  [ local file header n     ]
  [ file data n             ]
  [ data descriptor n       ]

  [ archive decryption header ] (optional)
  [ archive extra data record ] (optional)

  [ central directory header 1 ]
  ...
  [ central directory header n ]

  [ zip64 end of central directory record  ] (optional)
  [ zip64 end of central directory locator ] (optional)
  [ end of central directory record        ]
```

`clj-zip-meta` parses the **non-optional** records — local file headers,
central directory headers, and the EOCDR.

## Status

`clj-zip-meta` solves a specific problem: reading and patching the
binary layer of zip files. Within that scope it is intended for
production use:

* Test coverage across reading, repairing, rebuilding from local
  headers, and the CLI.
* CI matrix across Clojure 1.10 / 1.11 / 1.12 on JDK 11 / 17 / 21.
* `*warn-on-reflection*` enabled with the project compiling cleanly.

Out of scope (no current plans):

* Zip64.
* Reading or decrypting encrypted entries.
* Extracting entry data (use `java.util.zip.ZipFile`).

## Building executable Clojure CLI tools

The original motivation for this library was supporting the `cli-cmd`
Leiningen template, which scaffolds Clojure command-line utilities and
uses `lein-binplus` to produce executable jars. Briefly:

```
$ lein new cli-cmd foo
$ cd foo
$ lein bin
Created /.../target/foo-0.1.0-SNAPSHOT.jar
Created /.../target/foo-0.1.0-SNAPSHOT-standalone.jar
Creating standalone executable: /.../target/foo
Re-aligning zip offsets
$ target/foo
...
```

`lein bin` prepends a small shell prelude to the standalone jar so it
runs without `java -jar`; `clj-zip-meta` is what makes the prepended
archive validate cleanly afterwards.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Copyright © 2017–2026 Matias Bjarland

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
