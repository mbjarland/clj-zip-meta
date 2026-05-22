# Introduction to clj-zip-meta

`clj-zip-meta` reads and patches the binary metadata of zip and jar files
as defined by the [PKWARE zip specification][zipspec]. It does **not**
extract entry data — for that, see `java.util.zip.ZipFile` or libraries
built on top of it.

This document walks through the on-disk structure of a zip file, what
this library exposes, and how to use it for common tasks.

[zipspec]: https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT

## The on-disk layout of a zip file

A zip archive is a stream of records laid out roughly like this:

```
  [ local file header 1 ]
  [ file data 1         ]
  [ optional data desc  ]
  ...
  [ local file header n ]
  [ file data n         ]
  [ optional data desc  ]

  [ central directory header 1 ]
  ...
  [ central directory header n ]
  [ end-of-central-directory record ]
```

The end-of-central-directory record (EOCDR) sits at the end of the file
and points back at where the central directory starts. The central
directory then lists every entry, with each entry's `relative-offset-local-header`
pointing back at the matching local file header near the front of the
file.

This library reads files the way the specification intends: it scans
**backwards from the end** for the EOCDR signature, then uses the offsets
recorded there to locate the central directory. That makes it tolerant
of archives that have arbitrary bytes prepended (self-extracting zips,
executable jars with a shell prelude, etc.) — situations where many
naive zip readers give up.

## Data shape

`zip-meta` returns a map with four keys:

| Key                  | Description |
| -------------------- | ----------- |
| `:extra-bytes`       | Number of bytes prepended before the real zip data (0 for a well-formed archive). |
| `:end-of-cdr-record` | `{:offset N :record { ... }}` describing the EOCDR. |
| `:cdr-records`       | Vector of `{:offset N :record {...}}` central directory entries. |
| `:local-records`     | Vector of `{:offset N :record {...}}` local file headers. |

Each `:record` map mirrors the fields of the corresponding zip
specification record (see `clj-zip-meta.spec`). The keys correspond
directly to the field names in section 4.3 of APPNOTE.TXT.

## Common tasks

### List entry names

```clojure
(require '[clj-zip-meta.core :as z])

(map :file-name (z/zip-entries "my.jar"))
;;=> ("META-INF/" "META-INF/MANIFEST.MF" "foo/Bar.class")
```

### Inspect the full metadata

```clojure
(z/zip-meta "my.jar")
;;=> {:extra-bytes 0
;;    :end-of-cdr-record {...}
;;    :cdr-records       [{...} {...}]
;;    :local-records     [{...} {...}]}
```

### Repair an archive with prepended bytes

If you prepend a script (e.g. a shell launcher for an executable jar)
the recorded offsets become stale and many tools refuse to read the
file. `repair-zip-with-preamble-bytes` rewrites the offsets in place
so the archive validates again.

```clojure
(z/repair-zip-with-preamble-bytes "my-exec.jar")
```

### Validate

`validate-zip-meta` checks that the recorded record signatures actually
appear at the expected offsets, that the EOCDR points to a real central
directory, and that the local headers line up. Pass `:repair true` to
automatically fix the most common drift (prepended bytes).

```clojure
(z/validate-zip-meta "my-exec.jar" :repair true)
```

## Going deeper

The `clj-zip-meta.spec` namespace defines the octet specs for each
record type — `rec-local-file-header`, `rec-cdr-header`, `rec-end-of-cdr`
— and their signature byte sequences. If you need to read or write
fields the high-level API does not cover, you can use these specs
directly with the `read-spec-from-file` / `write-spec-to-file!` helpers
in `clj-zip-meta.core`.
