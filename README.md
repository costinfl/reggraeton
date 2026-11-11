# reggraeton

Converts existing Cucumber-JVM BDD suites (Gherkin `.feature` files + Java step definitions,
typically driving Selenium) into **Playwright for Java** regression tests: one Playwright
test per existing scenario, runnable headless or windowed against a running web app, locally
or from Jenkins, using an **already installed browser** (Microsoft Edge by default; no browser
downloads).

> Status: under construction — see [docs/PROGRESS.md](docs/PROGRESS.md) for what is done.

## Building

Requires JDK 17+ and Maven 3.9+. Nothing is downloaded at run time, and no browser is ever
downloaded.

```
mvn package            # builds and tests; produces reggraeton-cli/target/reggraeton.jar
bin/reggraeton --help  # bin\reggraeton.cmd on Windows
```

## 1. Assess a repository (`assess`)

```
bin/reggraeton assess path/to/legacy-repo --out reggraeton-report
```

Read-only. Scans the repository and writes:

| File | Content |
|------|---------|
| `report.html` | Self-contained report: readiness per scenario (with each step's step definition), features, findings, Selenium API usage, step definitions, project layout |
| `report.md` | The same in Markdown |
| `traceability.csv` | One row per existing test (scenario or Examples row) → the Playwright test generated for it, with the command that runs only that test |
| `callgraph.dot` / `callgraph.json` | Calling graph: scenarios → step definitions / hooks → page objects and helpers, with Selenium call counts (render with `dot -Tsvg callgraph.dot -o callgraph.svg`) |
| `inventory.json` | The complete model (input contract for `convert`) |

Each test case is rated:

- **ready**: every Selenium call reachable from its steps and hooks has a translation rule;
- **partial**: it will be generated, but some calls become `TODO(reggraeton)` stubs that fail until finished;
- **blocked**: undefined or ambiguous steps; it will be generated as a failing test that states the reason.

Options: `--classpath cp.txt` (jars of the scanned project, from
`mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`, for better type resolution),
`--base-package` (package of the generated tests), `--fail-on none|error|blocked|partial` (exit
code 2 for CI gates).

## 2. Generate the Playwright project (`convert`)

```
bin/reggraeton convert path/to/legacy-repo --out ../shop-regression --mode bdd     # or --mode native
cd ../shop-regression
mvn test -DbaseUrl=http://localhost:8080                 # headless, installed Microsoft Edge
mvn test -DbaseUrl=http://localhost:8080 -Dheadless=false  # windowed
```

- **bdd**: the feature files are copied unchanged and run by Cucumber 8 on the JUnit Platform;
  step definitions, hooks and page objects are translated from Selenium to Playwright.
- **native**: one plain JUnit 5 class per feature file and one test per scenario (outlines become
  parameterized tests, one invocation per Examples row); no Cucumber at run time.

Both modes: one test per original scenario / Examples row (checked, exit code 3 otherwise),
`-Dcucumber.filter.tags=...` selection, `-Dbrowser.channel=msedge|chrome|chromium` or
`-Dbrowser.executable=...`, a `Jenkinsfile`, `traceability.csv`, and `conversion-report.md`
listing every `TODO(reggraeton)` left for a human. Code without an automatic Playwright
equivalent is never silently dropped: it throws `PendingConversionException` until rewritten.

## Planned

```
reggraeton doctor                                             # check the local browser setup
```

## Repository layout

| Path | Purpose |
|------|---------|
| `reggraeton-core` | scanning, matching, call graph, reports, translation and generation |
| `reggraeton-runtime` | support classes copied into generated projects (browser session, waits, ...) |
| `reggraeton-cli` | command line (`reggraeton.jar`), launchers in `bin/` |
| `samples/legacy-shop` | sample Cucumber + Selenium suite used as test fixture |
| `docs/` | progress log and documentation |
