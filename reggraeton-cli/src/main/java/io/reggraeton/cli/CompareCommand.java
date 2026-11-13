package io.reggraeton.cli;

import io.reggraeton.compare.ComparisonWriter;
import io.reggraeton.compare.RunComparison;
import io.reggraeton.compare.TestRun;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/** {@code reggraeton compare}: compares the JUnit results of two runs of the same suite. */
@Command(
        name = "compare",
        mixinStandardHelpOptions = true,
        description = {
                "Compares two runs of the same test suite (e.g. against the old and the new build of an",
                "application, or two releases) from their JUnit XML reports (TEST-*.xml) and lists the",
                "regressions, fixes and tests that fail for a different reason."})
public final class CompareCommand implements Callable<Integer> {

    /** When to return a non-zero exit code. */
    public enum FailOn { none, regression, difference }

    @Parameters(index = "0", paramLabel = "<runA>",
            description = "Baseline: a generated project, a surefire-reports folder (or a copy of it), or one TEST-*.xml.")
    Path runA;

    @Parameters(index = "1", paramLabel = "<runB>", description = "Run to compare with the baseline.")
    Path runB;

    @Option(names = "--label-a", paramLabel = "<name>", defaultValue = "A", description = "Name of run A (default: ${DEFAULT-VALUE}).")
    String labelA;

    @Option(names = "--label-b", paramLabel = "<name>", defaultValue = "B", description = "Name of run B (default: ${DEFAULT-VALUE}).")
    String labelB;

    @Option(names = {"-o", "--out"}, paramLabel = "<dir>", defaultValue = "reggraeton-compare",
            description = "Output directory for compare.html/.md/.json (default: ${DEFAULT-VALUE}).")
    Path out;

    @Option(names = "--fail-on", paramLabel = "<level>", defaultValue = "none",
            description = {"Exit with code 2 on: none (default), regression (passed in A and fails in B, or",
                    "fails for a different reason), difference (any change, incl. fixes and missing tests)."})
    FailOn failOn;

    @picocli.CommandLine.Spec
    picocli.CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        PrintWriter out = spec.commandLine().getOut();
        TestRun a = TestRun.read(runA);
        TestRun b = TestRun.read(runB);
        if (a.results().isEmpty() || b.results().isEmpty()) {
            spec.commandLine().getErr().println("No JUnit reports (TEST-*.xml) found in "
                    + (a.results().isEmpty() ? runA : runB) + ". Run the tests first (mvn test).");
            return 1;
        }
        RunComparison comparison = new RunComparison(labelA, a, labelB, b);
        ComparisonWriter.write(comparison, this.out);
        out.print(ComparisonWriter.summary(comparison));
        out.println("Report: " + this.out.toAbsolutePath().normalize().resolve("compare.html"));
        out.flush();
        boolean fail = switch (failOn) {
            case none -> false;
            case regression -> comparison.hasRegressions();
            case difference -> comparison.hasDifferences();
        };
        return fail ? 2 : 0;
    }
}
