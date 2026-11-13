# Implementation progress

The implementation is split into phases. Each phase ends with a commit on branch
`claude/bdd-playwright-converter-i6lpw9`, with a green `mvn test`. The table is the resume
point: pick up the first phase that isn't done.

| # | Phase | Status |
|---|-------|--------|
| 1 | Project skeleton, scan model, Gherkin parsing, layout detection, sample fixture | done |
| 2 | Step-definition / hook / parameter-type discovery, step matching, call graph | done |
| 3 | `assess` command: inventory.json, HTML/MD report, call graph DOT/JSON, traceability.csv; CLI module | done |
| 4 | Selenium → Playwright translation rule engine + runtime support sources | done |
| 5 | `convert --mode bdd` and `--mode native` generators, Jenkinsfile/README templates, completeness gate | done |
| 6 | `doctor`, demo web app, end-to-end run of both generated projects, docs | done |

## Phase 1 (done)

- Maven multi-module build (`pom.xml`, `reggraeton-core`), Java 17 baseline.
- `samples/legacy-shop`: a typical Cucumber-JVM 7 + Selenium 4 suite used as the test fixture.
  3 feature files, 10 executable scenarios (incl. Scenario Outline with two Examples blocks,
  Rules, Background, data table, doc string, tags), annotation-, lambda- (cucumber-java8) and
  regex-style step definitions, a custom `@ParameterType`, page objects with `@FindBy`,
  picocontainer-injected shared state, hooks. Deliberate problems for the assessment to find:
  one undefined step (`I see search suggestions`), one unused step definition (`I log out`) and
  one Selenium API with no direct Playwright equivalent (`driver.manage().logs()`).
- `io.reggraeton.model`: inventory model (records, serialised as JSON).
- `FeatureParser`: official Cucumber Gherkin parser + pickle compiler, so every Scenario and every
  Examples row becomes exactly one `TestCase` with a stable id `<feature path>:<line>`.
- `ProjectLayoutDetector`: build tool, Java source roots, feature roots, runners, glue packages
  (JUnit 5 suite, `@CucumberOptions`, `junit-platform.properties`/`cucumber.properties`), DI
  container, library versions from Maven/Gradle build files.
- `RepositoryScanner`: orchestrates the above into an `Inventory`.

## Phase 2 (done)

- `scan.java.JavaSourceIndex`: parses every Java file once (JavaParser, Java 21 syntax) and injects
  the symbol solver: the repo's own source roots (derived from package declarations), the JDK, and
  the Selenium 4 API, which is on reggraeton's classpath for type resolution only. Extra jars of
  the scanned project can be passed in to improve resolution.
- `GlueScanner`: annotated steps (any Gherkin language package, repeated annotations, constant
  concatenation and `static final` constants), cucumber-java8 lambdas, hooks (tag expression,
  order), `@ParameterType` (+ java8), `@DataTableType`/`@DocStringType`.
- `StepMatcher`: Cucumber's own expression engine (Cucumber Expressions, regex, custom parameter
  types) → matched / undefined / ambiguous, with extracted arguments.
- `CallGraphBuilder`: nodes for every method, constructor, glue lambda and class-level locators
  (`@FindBy`, field initialisers); edges resolved through the symbol solver, with name/arity
  fallbacks marked `resolved=false`; every external call is classified by `ApiClassifier`
  (selenium / driver-setup / assertion / cucumber / external) against `SeleniumApiCatalog`
  (`auto` = has a translation rule, `manual` = needs a human; some rules depend on arguments).
- `Assessor`: hooks applied per test case via Cucumber tag expressions, reachability from steps and
  hooks, status `ready` / `partial` / `blocked`, and findings (`UNDEFINED_STEP`, `AMBIGUOUS_STEP`,
  `UNUSED_STEP_DEFINITION`, `MANUAL_CONVERSION`, `OUTSIDE_GLUE`, parse errors, DI warnings).
- Glue outside the configured glue packages is ignored, as Cucumber does.
- On the fixture: 19 step definitions, 3 hooks, 8 ready / 1 partial / 1 blocked test cases.

## Phase 3 (done)

- `naming.TestNaming`: the one place that names generated tests (native: `<base>.features.<dir>.<Feature>Test#<scenario>`,
  outline rows as `[n]` invocations; bdd: copied feature path + line). Common feature directory,
  `src/*/resources` compaction for multi-module repos, collision handling, Java keyword safety.
- `report`: `ReportData` (aggregates), `HtmlReport` (self-contained, light/dark, filterable tables,
  per-scenario step → step definition details, works at phone width), `MarkdownReport`,
  `CallGraphExport` (DOT + JSON, outline rows folded per scenario, only code reachable from used
  glue), `Traceability` (CSV, RFC 4180), `AssessmentWriter`.
- `reggraeton-cli`: picocli `assess` command (`--out`, `--classpath`, `--base-package`,
  `--fail-on`), shaded `reggraeton.jar`, `bin/reggraeton` and `bin/reggraeton.cmd` launchers.
- 33 tests green.

## Phase 4, part 1 (done): `reggraeton-runtime`

Compiled and tested module; its sources are copied into generated projects (package renamed).

- `RunConfig`: `baseUrl`/`BASE_URL`, `headless`/`HEADLESS`, `browser.channel`/`BROWSER_CHANNEL`
  (default `msedge`), `browser.executable`, `slowMo`, `timeout`, `navigationTimeout`, `viewport`,
  `trace` (`on-failure`), `artifacts.dir`.
- `PlaywrightSession`: browser launched once per thread from an installed channel/executable,
  `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` always set; new context + page per test with baseURL,
  timeouts, tracing; screenshot + trace on failure; clear error when the browser is missing.
- `Waits` (WebDriverWait/ExpectedConditions equivalents as web-first waits), `Locators.lazy`/`lazyAll`
  (`@FindBy` equivalents), `Keys` + `Elements.sendKeys` (Selenium key/chord semantics, caret at end),
  `Screenshots`, `PendingConversion`/`PendingConversionException`.
- Browser tests are opt-in: `mvn test -pl reggraeton-runtime -Dreggraeton.browserTests=true -Dbrowser.channel=chromium`
  (verified here with the pre-installed Chromium; nothing downloaded).

## Phase 4, part 2 (done): translator (`io.reggraeton.translate`)

- `Translator` selects the legacy files to convert (glue used by scenarios, everything reachable,
  referenced project types, parameter types; runners excluded) and runs `SourceTranslator` on each.
- `SourceTranslator`: pass 1 resolves/plans every call on the untouched AST; pass 2 applies:
  - `By.*` → Playwright selector strings (`#id`, css, `xpath=`, `[name=..]`, `a:text-is(..)`),
    `findElement` → `locator(..)` (+ `.first()` unless the selector is an id, to keep Selenium's
    first-match semantics), `findElements` → `.all()`;
  - element calls (`click`, `getText`→`innerText`, `getAttribute("value")`→`inputValue`, `isSelected`→`isChecked`, ...);
    `clear(); sendKeys(text)` → `fill(text)`, other `sendKeys` → `press` / `Elements.sendKeys`;
  - `WebDriverWait`/`FluentWait` → `Waits`, `until(ExpectedConditions.x(..))` → `wait.x(..)`;
  - `Select`, `Actions` chains (→ sequential locator calls), `executeScript`, screenshots,
    navigation, cookies, window/timeouts (removed with a comment), `PageFactory` (removed);
  - `@FindBy`/`@FindBys`/`@FindAll` fields → `Locators.lazy(() -> this.driver.locator(..))`;
  - driver creation (`new ChromeDriver`, WebDriverManager, options) → `PlaywrightSession.current().page()` / removed;
  - `System.getProperty("app.url"...)`-style base URL lookups → `RunConfig.baseUrl()`;
  - types (`WebDriver`→`Page`, `WebElement`/`Select`→`Locator`, `By`→`String`, waits, exceptions,
    other Selenium types → `Object`), multi-catch de-duplication, imports.
  - anything without a rule → statement-level `// TODO(reggraeton) ...` + `PendingConversion.fail(..)`
    (keeps the file compilable, fails at run time with file:line of the original code).
- `RuntimeSources`: runtime support sources packaged in the core jar, re-packaged on demand.
- Tests: rule snippets, stubs, driver setup, catalogue ⊆ rules, and the whole translated fixture +
  runtime compiled with javac against Playwright 1.63 + Cucumber 8.0.4. 46 tests green.

## Phase 5 (done): `convert`

- `generate.ProjectGenerator` writes a standalone Maven project: translated glue, runtime support
  sources (per mode), `pom.xml` (Playwright 1.63.0, JUnit 6.1.3, Cucumber 8.0.4 in bdd mode,
  assertion libraries the legacy code used; Surefire always sets `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`),
  `Jenkinsfile` (BASE_URL / TAGS / BROWSER_CHANNEL / HEADLESS parameters, sh/bat, JUnit results,
  artifacts), `README.md`, `.gitignore`, `traceability.csv`, `conversion-report.md`.
- Completeness gate: every test case id must be covered by a generated test (exit code 3 otherwise).
- bdd: features copied unchanged to `src/test/resources/features/...` (per `TestNaming`), suite
  runner `RunRegressionTest` with the glue packages + support package, `PlaywrightHooks` (session
  around each scenario, screenshot attached on failure).
- native: `NativeGlueRewriter` drops Cucumber annotations, turns cucumber-java8 lambdas into
  methods (captured constructor params/locals become fields), swaps `Scenario`/`DataTable` for
  runtime equivalents; `NativeTestWriter` writes a JUnit class per feature (outlines as
  `@ParameterizedTest` + `Named` rows), hooks chosen by tag expression at generation time and
  ordered like Cucumber, `ArgumentRenderer` converts step arguments (built-in types, custom
  parameter types through their converter method, data tables / doc strings, enums); undefined,
  ambiguous or unconvertible steps fail with the reason. Runtime: `RegressionTest`, `Scenario`,
  `DataTable`, `ScenarioScope` (picocontainer-like), `TagFilter` (`-Dcucumber.filter.tags` in both modes).
- CLI: `reggraeton convert <repo> --out <dir> --mode bdd|native [--base-package] [--force] ...`.
- Verified: both generated projects compile with javac in tests and with real `mvn test-compile`.
  57 tests green.

## Phase 6 (done): end to end

- `samples/demo-app`: static demo shop + JDK-only `DemoServer.java` matching the sample suite.
- End-to-end runs here, with the pre-installed Chromium (`-Dbrowser.channel=chromium`), nothing downloaded:
  - native and bdd: 10 tests each, 8 pass, 2 fail by design (TODO stub with original file:line,
    undefined step), screenshot + trace saved for each failure;
  - windowed (`-Dheadless=false`, under xvfb) with `-Dcucumber.filter.tags=@smoke`: 2 run, 8 skipped, both modes;
  - single test: `-Dcucumber.features=...login.feature:27` (bdd), `-Dtest=LoginTest#rejectedLogin` (native);
  - default channel `msedge` (not installed in the sandbox): immediate, explanatory failure, no download.
- Fixes found by the runs: Cucumber 8 needs Jackson for its HTML report (added to bdd poms);
  element lists wait for the page load like Selenium (`Elements.findAll`, `Locators.lazyAll`,
  optional `-DimplicitWait`).
- `reggraeton doctor` (Java, Maven, installed Edge/Chrome/Chromium per OS; launches nothing),
  `reggraeton rules` (→ `docs/rules.md`), `scripts/e2e.sh`, README.
- 56 tests green (+ opt-in browser tests).

## Later additions

- `reggraeton compare`: JUnit XML of two runs bucketed into regression / different failure / fixed /
  only in A or B / skipped / same failure / passed; bdd runners use Cucumber's surefire naming.
- Database access in `assess` (`io.reggraeton.scan.db`): database library calls are a `database`
  API category (kept by the translator); SQL in Java literals, concatenations and constants becomes
  `SQL <op>` usages on the call graph node that runs it; SQL scripts, mapper XML, configuration and
  feature files are scanned as text; connection strings and settings with passwords masked.
  `DATABASE_HOOK` warnings for hooks reaching the database; report section with tests reaching each
  hook, step, method and statement. Sample suite: `Database` helper (no-op without `-Ddb.url`) used
  by the `@cart` hook, `db.properties`, `testdata/reset.sql`.

## Not verified here

- Microsoft Edge itself (not installed in this sandbox): the `msedge` channel is Playwright's
  standard channel; first real run is on a machine with Edge (`scripts/e2e.sh` defaults to it).
- Jenkins: the `Jenkinsfile` is generated and reviewed but was not executed on a Jenkins server.
- Windows: `bin/reggraeton.cmd` and the `bat` branch of the Jenkinsfile were not run.

## Possible next steps

- Run `assess` on a real legacy repository and extend the rule catalogue with what it reports.
- Translate frames (`switchTo().frame` → `frameLocator`) and alerts (`onceDialog`) automatically.
- Native mode: `@BeforeAll`/`@AfterAll` hooks via a JUnit extension; custom `@DataTableType`.
