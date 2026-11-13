package io.reggraeton.cli;

import io.reggraeton.model.Inventory;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.naming.TestNaming;
import io.reggraeton.report.AssessmentWriter;
import io.reggraeton.scan.RepositoryScanner;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code reggraeton assess}: read-only discovery and readiness assessment of a repository. */
@Command(
        name = "assess",
        mixinStandardHelpOptions = true,
        description = {
                "Scans a Cucumber-JVM repository (read-only) and writes an assessment: feature files, step",
                "definitions, hooks, the calling graph into page objects and Selenium, conversion readiness",
                "per scenario, and the Playwright test each scenario will become."})
public final class AssessCommand implements Callable<Integer> {

    /** When to return a non-zero exit code (for CI gates). */
    public enum FailOn { none, error, blocked, partial }

    @Parameters(index = "0", paramLabel = "<repo>", description = "Root of the repository to scan.")
    Path repository;

    @Option(names = {"-o", "--out"}, paramLabel = "<dir>", defaultValue = "reggraeton-report",
            description = "Output directory (default: ${DEFAULT-VALUE}).")
    Path out;

    @Option(names = "--classpath", paramLabel = "<file|entries>",
            description = {"Jars of the scanned project, to improve type resolution: a file as written by",
                    "'mvn dependency:build-classpath -Dmdep.outputFile=cp.txt', or a path-separated list."})
    String classpath;

    @Option(names = "--base-package", paramLabel = "<package>", defaultValue = TestNaming.DEFAULT_BASE_PACKAGE,
            description = "Package of the generated tests, used for planned test names (default: ${DEFAULT-VALUE}).")
    String basePackage;

    @Option(names = "--fail-on", paramLabel = "<level>", defaultValue = "none",
            description = "Exit with code 2 when: none, error (any error finding), blocked (any blocked test), "
                    + "partial (any test not fully ready). Default: ${DEFAULT-VALUE}.")
    FailOn failOn;

    @Option(names = "--config", paramLabel = "<file>",
            description = "reggraeton.properties with rules for external library calls (default: <repo>/reggraeton.properties, if present).")
    Path config;

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter console = spec.commandLine().getOut();
        List<Path> jars = Classpath.parse(classpath);
        console.printf("Scanning %s ...%n", repository.toAbsolutePath().normalize());
        Inventory inventory = new RepositoryScanner(jars, config).scan(repository);
        List<Path> files = AssessmentWriter.write(inventory, out, basePackage);

        long ready = count(inventory, TestCaseAssessment.READY);
        long partial = count(inventory, TestCaseAssessment.PARTIAL);
        long blocked = count(inventory, TestCaseAssessment.BLOCKED);
        long errors = inventory.findings().stream().filter(f -> "error".equals(f.severity())).count();
        long warnings = inventory.findings().stream().filter(f -> "warning".equals(f.severity())).count();

        console.printf("%n  feature files      %d%n", inventory.features().size());
        console.printf("  test cases         %d  (ready %d, partial %d, blocked %d)%n",
                inventory.testCases().size(), ready, partial, blocked);
        console.printf("  step definitions   %d, hooks %d%n", inventory.stepDefinitions().size(), inventory.hooks().size());
        long dbHooks = inventory.findings().stream().filter(f -> "DATABASE_HOOK".equals(f.code())).count();
        long dbStatements = inventory.database().statements().size();
        console.printf("  database           %d hooks with database access, %d SQL statements, %d connection settings%n",
                dbHooks, dbStatements, inventory.database().connections().size());
        console.printf("  findings           %d errors, %d warnings%n%n", errors, warnings);
        console.printf("Report: %s%n", files.get(0));
        console.printf("Also written to %s: %s%n", files.get(0).getParent(),
                String.join(", ", files.subList(1, files.size()).stream().map(p -> p.getFileName().toString()).toList()));

        boolean fail = switch (failOn) {
            case none -> false;
            case error -> errors > 0;
            case blocked -> blocked > 0 || errors > 0;
            case partial -> partial > 0 || blocked > 0 || errors > 0;
        };
        return fail ? 2 : 0;
    }

    private static long count(Inventory inventory, String status) {
        return inventory.assessments().stream().filter(a -> status.equals(a.status())).count();
    }
}
