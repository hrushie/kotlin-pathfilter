# pathfilter

A small command-line tool that decides which CI/CD pipelines or jobs should
run, based on the files changed in a commit or merge request.

It is written in Kotlin and built with Gradle. It reads a YAML file of named
filters (glob patterns), compares them against a list of changed file paths,
and writes which filters matched in a simple env-file format that any CI
system can consume — a GitLab dotenv report, GitHub Actions step outputs, or
plain shell sourcing.

```
changed files ──▶ ┌─────────────┐ ──▶  backend=true
filters.yaml  ──▶ │ pathfilter  │ ──▶  frontend=false
                  └─────────────┘ ──▶  docs=true
```

## Highlights

- Self-contained: no third-party runtime dependencies. A small built-in parser
  handles the `filters.yaml` format, so the produced jar needs nothing but a
  JVM.
- Correct glob semantics: `*` stays within a path segment, `**` crosses
  segments, and a leading `**/` also matches a top-level file.
- Exclusion patterns with `!`.
- Flexible input: positional paths, `--stdin`, or `--files-from` (ideal for
  piping `git diff --name-only`).
- Deterministic output in declaration order, ready for
  `artifacts:reports:dotenv`.

## Requirements

A JDK 21 installation. The Gradle wrapper (`./gradlew`) fetches the correct
Gradle version automatically.

## Build

```bash
# Compile and run the test suite
./gradlew build

# Produce a runnable distribution under build/install/pathfilter/
./gradlew installDist

# Or produce a single self-contained executable jar
./gradlew executableJar
```

After `installDist`, a launcher script is available at
`build/install/pathfilter/bin/pathfilter`.

`executableJar` bundles the compiled classes and the Kotlin stdlib into one
file, `build/libs/pathfilter-all.jar`, that runs anywhere with just a JVM and
no Gradle:

```bash
java -jar build/libs/pathfilter-all.jar -c examples/filters.yaml App.kt README.md
```

Build it once; you only need to rebuild after changing `src/`. In CI it is
usually better to build (or download) the jar once and cache it than to
rebuild the tool on every pipeline run.

## Usage

```
pathfilter -c FILE [-o FILE] [--stdin] [--files-from FILE] [PATH ...]

  -c, --config FILE     path to the filters YAML file (required)
  -o, --output FILE     write the env file here (default: stdout)
      --stdin           read newline-delimited changed paths from stdin
      --files-from FILE read changed paths from a file ('-' for stdin)
  PATH ...              changed file paths as positional arguments
```

Changed files from all three sources (positional, `--stdin`, `--files-from`)
are merged and de-duplicated.

### Examples

```bash
PF=build/install/pathfilter/bin/pathfilter

# Positional paths, result to stdout
$PF -c examples/filters.yaml app/src/main/App.kt README.md

# Pipe from git and write a dotenv file for CI to consume
git diff --name-only origin/main...HEAD \
  | $PF -c examples/filters.yaml --stdin -o filters.env

# Read the changed-file list from a file
$PF -c examples/filters.yaml --files-from changed.txt -o filters.env
```

## Configuration format

```yaml
filters:
  kotlin_sources:
    - "app/src/**/*.kt"
    - "!app/src/test/**"      # exclusion: don't trigger on test-only changes
  tests:
    - "**/test/**/*.kt"
  documentation:
    - "README.md"
    - "docs/**/*.md"
```

Each key under `filters:` is a filter name; its value is a list of glob
patterns (a single pattern string also works). A pattern beginning with `!`
is an exclusion.

### Matching semantics

| Token | Meaning |
|-------|---------|
| `*`   | matches any characters within a single path segment (never crosses `/`) |
| `**`  | matches zero or more whole path segments (may cross `/`) |
| `**/` | matches zero or more leading directories, so `**/*.kt` also matches a top-level `App.kt` |
| `?`   | matches exactly one character within a segment |
| `!`   | prefix marking an exclusion pattern |

Patterns are matched against the entire path.

### Evaluation rule

A filter is `true` when at least one changed file:

1. matches at least one inclusion pattern, and
2. matches none of the exclusion patterns.

Otherwise it is `false`.

## Output format

An env file with one `name=true|false` line per filter, in declaration order:

```
backend=true
frontend=false
docs=true
```

When `-o` is used the file is written and a one-line summary is printed to
stderr (so CI logs still show the result); otherwise the env file goes to
stdout.

## Worked example

`filters.yaml`:

```yaml
filters:
  kotlin_sources:
    - "**/*.kt"
    - "!**/test/**"
  tests:
    - "**/test/**/*.kt"
  documentation:
    - "README.md"
    - "docs/**"
```

```bash
build/install/pathfilter/bin/pathfilter -c filters.yaml \
  App.kt app/src/test/FiltersTest.kt README.md
```

Output:

```
kotlin_sources=true
tests=true
documentation=true
```

- `kotlin_sources` → `App.kt` matches `**/*.kt` and isn't under `test/`.
- `tests` → `FiltersTest.kt` is under a `test/` directory.
- `documentation` → `README.md` matches.

If only a test file changed, the exclusion kicks in:

```bash
build/install/pathfilter/bin/pathfilter -c filters.yaml app/src/test/FiltersTest.kt
# kotlin_sources=false   (excluded by !**/test/**)
# tests=true
# documentation=false
```

## CI integration

### GitLab CI

Run pathfilter in an early job and publish `filters.env` as a dotenv report;
jobs that `needs:` the detect job then receive each filter result as a plain
environment variable:

```yaml
detect-changes:
  script:
    - git diff --name-only "$CI_MERGE_REQUEST_DIFF_BASE_SHA" HEAD
      | java -jar pathfilter-all.jar -c filters.yaml --stdin -o filters.env
  artifacts:
    reports:
      dotenv: filters.env
```

One caveat: GitLab cannot evaluate dotenv-report variables inside a
downstream job's `rules:if` — that is a
[documented GitLab limitation](https://docs.gitlab.com/ci/variables/dotenv_variables/),
not a pathfilter bug. A job gated with `rules: - if: '$backend == "true"'`
will simply never run. Dotenv variables *are* injected into every downstream
job's `script`, though, so gate there instead:

```yaml
backend:
  needs: [detect-changes]
  script:
    - if [ "$backend" != "true" ]; then echo "skip: backend filter did not match"; exit 0; fi
    - ./build-backend.sh
```

The job still appears in the pipeline (as a quick "passed" no-op) when its
filter doesn't match — the trade-off for keeping `filters.env` as the single
source of truth.

### GitHub Actions

The `name=true|false` output format is exactly what `$GITHUB_OUTPUT` expects,
so pathfilter's results plug straight into step-level `if:` conditions — and
unlike GitLab, GitHub Actions *can* skip steps and jobs based on them:

```yaml
- name: Detect source changes
  id: changes
  run: |
    git diff --name-only "${{ github.event.before }}" "${{ github.sha }}" \
      | java -jar pathfilter-all.jar -c filters.yaml --stdin >> "$GITHUB_OUTPUT"

- name: Build backend
  if: steps.changes.outputs.backend == 'true'
  run: ./build-backend.sh
```

To gate whole jobs instead of steps, run the detect step in a first job,
re-expose the step outputs as job `outputs:`, and reference them from
downstream jobs' `if:` via `needs.<job>.outputs.<filter>`.

## Development

```bash
# Run the test suite
./gradlew test

# Compile, test, and assemble
./gradlew build

# Run the CLI directly
./gradlew run --args="-c examples/filters.yaml App.kt README.md"
```

The source lives under `src/main/kotlin/com/pathfilter/`:

| File | Responsibility |
|------|----------------|
| `Filters.kt` | the `Filter` type, glob → regex translation, and `evaluateFilters()` |
| `FilterConfig.kt` | loading and validating `filters.yaml`, including the built-in YAML parser |
| `Main.kt` | argument parsing, env rendering, and the CLI entry point |

Tests are in `src/test/kotlin/com/pathfilter/FilterTest.kt` and cover the glob
semantics, exclusion handling, result ordering, and config validation.

## Design notes

- Glob patterns are translated to anchored regular expressions
  ([`Filters.kt`](src/main/kotlin/com/pathfilter/Filters.kt)). This keeps
  matching fast and predictable and makes the segment-vs-globstar distinction
  explicit. Compiled regexes are cached so a pattern is only translated once
  per run.
- Depending on a YAML library would add weight to the produced jar for no
  benefit here; a small parser for the documented subset keeps the tool
  self-contained ([`FilterConfig.kt`](src/main/kotlin/com/pathfilter/FilterConfig.kt)).
- Filters are emitted in the order declared (via `LinkedHashMap`), so the env
  file is stable and diff-friendly.

## License

MIT — see [LICENSE](LICENSE).
