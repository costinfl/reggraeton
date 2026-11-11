# reggraeton

Converts existing Cucumber-JVM BDD suites (Gherkin `.feature` files + Java step definitions,
typically driving Selenium) into **Playwright for Java** regression tests: one Playwright
test per existing scenario, runnable headless or windowed against a running web app, locally
or from Jenkins, using an **already installed browser** (Microsoft Edge by default; no browser
downloads).

> Status: under construction — see [docs/PROGRESS.md](docs/PROGRESS.md) for what is done.

## Planned commands

```
reggraeton assess  <repo> [--out reggraeton-report]          # discovery + assessment, read-only
reggraeton convert <repo> --out <dir> --mode bdd|native       # generate a Playwright project
reggraeton doctor                                             # check the local browser setup
```

## Building

Requires JDK 17+ and Maven 3.9+.

```
mvn test
```

## Repository layout

| Path | Purpose |
|------|---------|
| `reggraeton-core` | scanning, matching, call graph, translation and generation |
| `samples/legacy-shop` | sample Cucumber + Selenium suite used as test fixture |
| `docs/` | progress log and documentation |
