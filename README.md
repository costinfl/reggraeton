# reggraeton

Converts existing Cucumber-JVM BDD suites (Gherkin `.feature` files + Java step definitions,
typically driving Selenium) into **Playwright for Java** regression tests: one Playwright test
per existing scenario and per Scenario Outline row, runnable headless or windowed against a
running web app, locally or from Jenkins, using an **already installed browser** (Microsoft
Edge by default). **Browsers are never downloaded.**

```
bin/reggraeton assess  <repo> [--out reggraeton-report]          # 1. discovery + assessment (read-only)
bin/reggraeton convert <repo> --out <dir> --mode bdd|native       # 2. generate the Playwright project
bin/reggraeton doctor  [--channel msedge|chrome|chromium]         # check Java, Maven, installed browsers
bin/reggraeton rules                                              # which Selenium APIs convert automatically
```

## Building

Requires JDK 17+ and Maven 3.9+.

```
mvn package            # builds and runs the tests; produces reggraeton-cli/target/reggraeton.jar
bin/reggraeton --help  # bin\reggraeton.cmd on Windows (or: java -jar reggraeton-cli/target/reggraeton.jar)
```

## 1. Assess a repository

```
bin/reggraeton assess path/to/legacy-repo --out reggraeton-report
```

Read-only. Finds feature files, step definitions (annotations, cucumber-java8 lambdas, regular
expressions, any Gherkin language), hooks, custom parameter types, the glue packages and DI
container, matches every step with Cucumber's own engine, and follows the calling graph from
each step definition and hook through page objects and helpers down to every Selenium call.

| File | Content |
|------|---------|
| `report.html` | Self-contained report: readiness per scenario (each step with its step definition), features, findings, Selenium API usage, step definitions, project layout |
| `report.md` | The same in Markdown |
| `traceability.csv` | One row per existing test (scenario or Examples row) → the Playwright test generated for it, with the command that runs only that test |
| `callgraph.dot` / `callgraph.json` | Calling graph: scenarios → step definitions / hooks → project code, with Selenium call counts (`dot -Tsvg callgraph.dot -o callgraph.svg`) |
| `inventory.json` | The complete model |

Every test case is rated **ready** (every reachable Selenium call has a translation rule),
**partial** (some calls will become `TODO(reggraeton)` stubs) or **blocked** (undefined or
ambiguous steps). Findings include undefined/ambiguous/unused steps, unparseable files, glue
outside the configured glue packages and every call site that needs manual work, with file:line.

Options: `--classpath cp.txt` (jars of the scanned project from
`mvn dependency:build-classpath -Dmdep.outputFile=cp.txt`, for better type resolution),
`--base-package`, `--fail-on none|error|blocked|partial` (exit code 2, for CI gates).

## 2. Generate the Playwright project

```
bin/reggraeton convert path/to/legacy-repo --out ../shop-regression --mode bdd     # or --mode native
cd ../shop-regression
mvn test -DbaseUrl=http://localhost:8080                   # headless, installed Microsoft Edge
mvn test -DbaseUrl=http://localhost:8080 -Dheadless=false  # windowed
```

| | **bdd** | **native** |
|---|---|---|
| Feature files | copied unchanged, run by Cucumber 8 on the JUnit Platform | compiled into tests; not needed at run time |
| Tests | one Cucumber scenario per scenario / Examples row | one JUnit 5 class per feature, one `@Test` per scenario, Scenario Outlines as `@ParameterizedTest` (one invocation per row) |
| Glue | step definitions, hooks, page objects translated to Playwright | same, plus Cucumber removed: annotations dropped, java8 lambdas turned into methods, hooks applied by tag expression |
| Single test | `mvn test -Dcucumber.features=src/test/resources/features/x.feature:12` | `mvn test -Dtest=XTest#scenarioName` |

Both modes: `-Dcucumber.filter.tags="@smoke and not @wip"`, `-Dbrowser.channel=msedge|chrome|chromium`
or `-Dbrowser.executable=<path>`, `-DslowMo`, `-Dtimeout`, `-Dtrace=on-failure|on|off`, the same
settings as environment variables (`BASE_URL`, `HEADLESS`, `BROWSER_CHANNEL`, ...), JUnit XML
results, screenshot + Playwright trace for every failed test, a `Jenkinsfile`, `traceability.csv`
and `conversion-report.md`.

`convert` checks that every original test case has a generated test (exit code 3 otherwise).
Code without an automatic Playwright equivalent is never dropped silently: it becomes a
`// TODO(reggraeton)` stub that throws `PendingConversionException` (naming the original
file:line) until it is rewritten, so affected tests fail rather than pass. Steps without a step
definition produce failing tests that say so. See [docs/rules.md](docs/rules.md) for the rules.

## No browser downloads

- Generated tests launch only an installed Chromium-based browser: the `msedge` channel by
  default, another channel, or an explicit executable. If it is missing they fail immediately
  with instructions; nothing is fetched.
- `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` is set by the runtime (passed to Playwright), by the
  generated `pom.xml` (Surefire) and by the generated `Jenkinsfile`.
- `reggraeton doctor` lists the installed browsers it can find without launching anything.

## Jenkins

The generated `Jenkinsfile` has parameters `BASE_URL`, `TAGS`, `BROWSER_CHANNEL`
(`msedge` default) and `HEADLESS` (default true), works on Linux (`sh`) and Windows (`bat`)
agents, publishes `target/surefire-reports/*.xml` and archives screenshots, traces and the
Cucumber HTML report. The agent needs JDK 17+, Maven and the browser.

## How it works

1. **Scan** (`io.reggraeton.scan`): official Gherkin parser + pickle compiler (exact Cucumber
   expansion of outlines and backgrounds), JavaParser with a symbol solver over the repository's
   sources and the Selenium API, Cucumber Expressions for matching, Cucumber tag expressions for hooks.
2. **Assess** (`io.reggraeton.report`): readiness, findings, reports, traceability.
3. **Translate** (`io.reggraeton.translate`): two-pass AST rewrite of the reachable code from
   Selenium to Playwright, driven by the same API catalogue the assessment uses.
4. **Generate** (`io.reggraeton.generate`): the Maven project for the chosen mode, with the
   runtime support classes from `reggraeton-runtime` copied in (no extra artifact repository needed).

## Known limits

- Java step definitions only (Kotlin/Groovy/Scala are not scanned).
- Calls into libraries outside the scanned repository (shared test frameworks) are not followed;
  they become TODO stubs. Passing `--classpath` improves type resolution but not translation.
- Spring/Guice-wired glue is flagged; native mode uses constructor injection like picocontainer.
- Frames, alerts, multiple windows, browser logs, cookies other than "delete all", low-level
  mouse/keyboard actions and custom wait conditions need manual conversion (listed as TODOs).
- `@BeforeAll`/`@AfterAll` hooks are not called in native mode (reported).

## Developing

```
mvn test                                              # unit + integration tests (no browser)
mvn test -pl reggraeton-runtime -Dreggraeton.browserTests=true -Dbrowser.channel=msedge
scripts/e2e.sh                                        # full end-to-end check against the demo shop
BROWSER_CHANNEL=chromium HEADLESS=false scripts/e2e.sh
```

`scripts/e2e.sh` builds reggraeton, starts `samples/demo-app` (JDK-only server), converts
`samples/legacy-shop` in both modes and runs the generated tests: 10 tests per mode, 8 pass and 2
fail by design (one `TODO(reggraeton)` stub, one undefined step).

| Path | Purpose |
|------|---------|
| `reggraeton-core` | scanning, matching, call graph, reports, translation and generation |
| `reggraeton-runtime` | support classes copied into generated projects (browser session, waits, ...) |
| `reggraeton-cli` | command line (`reggraeton.jar`), launchers in `bin/` |
| `samples/legacy-shop` | sample Cucumber 7 + Selenium 4 suite used as fixture |
| `samples/demo-app` | demo web shop the sample suite tests |
| `docs/` | translation rules ([rules.md](docs/rules.md)) and implementation log ([PROGRESS.md](docs/PROGRESS.md)) |
