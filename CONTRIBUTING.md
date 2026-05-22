# Contributing to clj-zip-meta

Thanks for your interest in helping out. Bug reports, fixes, and small
new features are all welcome.

## Development setup

You'll need Leiningen 2.9+ and a JDK 11 / 17 / 21.

```
git clone https://github.com/mbjarland/clj-zip-meta.git
cd clj-zip-meta
lein test
```

The default `:dev` profile turns on `*warn-on-reflection*`. The
project itself is reflection-clean; please keep it that way. `lein
check` is a useful sanity check — only warnings from inside `octet/*`
should appear.

To exercise the project against more than one Clojure release:

```
lein test-all
```

This runs the suite three times against Clojure 1.10, 1.11, and 1.12
via Leiningen profiles named after the version.

## Code style

* Two-space indentation, no tabs.
* Public functions get a docstring. Internal helpers don't need one
  unless they encode a subtle invariant.
* Prefer existing helpers (`with-raf`, `read-spec-bb`, `write-spec-bb!`)
  over re-implementing resource management or octet plumbing.
* Avoid hidden reflection. Add `^Type` annotations or `(long x)` /
  `(int x)` coercions when needed.
* Don't use third-party formatting tools — the existing style is what
  we've got.

## Tests

Tests live under `src/test/` and use `clojure.test`. Anything that
mutates a file uses `copy-to-tmp` so the bundled fixtures stay
untouched and the temp files are cleaned up by the JVM on exit. New
features should come with tests; bug fixes should come with a
regression test.

When you add a test that exercises a write-side path
(`repair-zip-with-preamble-bytes`, `rebuild-central-directory!`,
`strip-preamble!`, `set-zip-comment!`), please assert post-conditions
via `validate-zip-meta` rather than checking individual bytes — the
goal is "the archive remains parseable", not a specific byte layout.

## Commits and pull requests

* One commit per logical change. If you have a large change, split it
  into a series of small, focused commits.
* Commit messages: one concise summary line (≤ 80 columns), one blank
  line, then a body explaining *why* the change is needed and any
  trade-offs. Wrap the body at 80 columns.
* Reference issue numbers in the body where relevant.
* Run `lein test` before pushing. CI runs the full matrix; if your
  change is platform-specific please call that out in the PR
  description.

## Releasing

Releases are cut by maintainers. The process is:

1. Update `:version` in `project.clj` and the `## [Unreleased]`
   heading in `CHANGELOG.md`.
2. Commit the version bump.
3. Tag the commit: `git tag v0.X.Y && git push --tags`.
4. The `release` GitHub Actions workflow runs the test suite once more
   and then `lein deploy clojars`.
