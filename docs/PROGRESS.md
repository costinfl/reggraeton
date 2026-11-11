# Implementation progress

The implementation is split into phases. Each phase ends with a commit on branch
`claude/bdd-playwright-converter-i6lpw9`, with a green `mvn test`. The table is the resume
point: pick up the first phase that isn't done.

| # | Phase | Status |
|---|-------|--------|
| 1 | Project skeleton, scan model, Gherkin parsing, layout detection, sample fixture | done |
| 2 | Step-definition / hook / parameter-type discovery, step matching, call graph | next |
| 3 | `assess` command: inventory.json, HTML/MD report, call graph DOT/JSON, traceability.csv; CLI module | todo |
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

## Next: phase 2

- `StepDefinitionScanner` (JavaParser): `@Given/@When/@Then/@And/@But` (incl. repeated),
  cucumber-java8 lambdas, hooks with tag expressions and order, `@ParameterType`, `@DataTableType`.
- `StepMatcher`: cucumber-expressions engine, regex steps, custom parameter types →
  matched / undefined / ambiguous per step; unused step definitions.
- `CallGraphBuilder`: step definition → project methods (symbol solver, name/arity fallback)
  → Selenium API calls, classified for readiness.
- Extend `Inventory` with step definitions, hooks, matches, call graph and findings.
