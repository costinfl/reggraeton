# Implementation progress

The implementation is split into phases. Each phase ends with a commit on branch
`claude/bdd-playwright-converter-i6lpw9`, with a green `mvn test`. The table is the resume
point: pick up the first phase that isn't done.

| # | Phase | Status |
|---|-------|--------|
| 1 | Project skeleton, scan model, Gherkin parsing, layout detection, sample fixture | done |
| 2 | Step-definition / hook / parameter-type discovery, step matching, call graph | done |
| 3 | `assess` command: inventory.json, HTML/MD report, call graph DOT/JSON, traceability.csv; CLI module | next |
| 4 | Selenium → Playwright translation rule engine + runtime support sources | todo |
| 5 | `convert --mode bdd` and `--mode native` generators, Jenkinsfile/README templates, completeness gate | todo |
| 6 | `doctor`, demo web app, end-to-end run of both generated projects, docs | todo |

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

## Next: phase 3

- New module `reggraeton-cli` (picocli, shaded runnable jar) with `assess <repo> [--out dir]
  [--classpath file]`.
- Writers: `inventory.json`, `report.html` + `report.md` (totals, per-feature table, readiness per
  scenario, undefined/ambiguous/unused, Selenium API histogram, manual work list with file:line),
  `callgraph.dot` + `callgraph.json`, `traceability.csv` (test case id → planned Playwright test).
- The planned test names must be the ones phase 5 generates: put the naming in one class
  (`naming.TestNaming`) used by both.
