package io.reggraeton.compare;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Compares two runs of the same test suite, e.g. against the Java 11 and the Java 17 build of an
 * application, or against two releases. A test does not have to pass: what matters is whether it
 * behaves the same way in both runs.
 */
public final class RunComparison {

    /** How a test changed from run A to run B. Declared in report order (most important first). */
    public enum Outcome {
        REGRESSION("passed in A, fails in B"),
        DIFFERENT_FAILURE("fails in both, for a different reason"),
        FIXED("failed in A, passes in B"),
        ONLY_IN_A("ran only in A"),
        ONLY_IN_B("ran only in B"),
        SKIPPED("skipped in at least one run"),
        SAME_FAILURE("fails in both, for the same reason"),
        PASSED("passes in both");

        private final String description;

        Outcome(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    /** One test in both runs. */
    public record Row(Outcome outcome, String key, String name, String testCaseId, TestRun.Result a, TestRun.Result b) {
    }

    private static final Pattern NOISE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" // uuids
                    + "|@[0-9a-fA-F]{4,}"                                                       // object ids
                    + "|\\d+");                                                                  // numbers, times, ports

    private final String labelA;
    private final String labelB;
    private final TestRun runA;
    private final TestRun runB;
    private final List<Row> rows = new ArrayList<>();

    public RunComparison(String labelA, TestRun runA, String labelB, TestRun runB) {
        this.labelA = labelA;
        this.labelB = labelB;
        this.runA = runA;
        this.runB = runB;
        Set<String> keys = new LinkedHashSet<>(runA.results().keySet());
        keys.addAll(runB.results().keySet());
        for (String key : keys) {
            TestRun.Result a = runA.results().get(key);
            TestRun.Result b = runB.results().get(key);
            TestRun.Result any = a != null ? a : b;
            String id = a != null && a.testCaseId() != null ? a.testCaseId() : b != null ? b.testCaseId() : null;
            rows.add(new Row(outcome(a, b), key, any.className() + " > " + any.name(), id, a, b));
        }
        rows.sort(Comparator.comparing(Row::outcome).thenComparing(Row::key));
    }

    static Outcome outcome(TestRun.Result a, TestRun.Result b) {
        if (a == null) {
            return Outcome.ONLY_IN_B;
        }
        if (b == null) {
            return Outcome.ONLY_IN_A;
        }
        if (a.status() == TestRun.Status.SKIPPED || b.status() == TestRun.Status.SKIPPED) {
            return Outcome.SKIPPED;
        }
        if (!a.failed() && !b.failed()) {
            return Outcome.PASSED;
        }
        if (!a.failed()) {
            return Outcome.REGRESSION;
        }
        if (!b.failed()) {
            return Outcome.FIXED;
        }
        return normalise(a.message()).equals(normalise(b.message())) ? Outcome.SAME_FAILURE : Outcome.DIFFERENT_FAILURE;
    }

    /** Failure message without the parts that change from run to run (numbers, ids, timings). */
    static String normalise(String message) {
        if (message == null) {
            return "";
        }
        return NOISE.matcher(message).replaceAll("#").replaceAll("\\s+", " ").trim();
    }

    public List<Row> rows() {
        return rows;
    }

    public Map<Outcome, Integer> counts() {
        Map<Outcome, Integer> counts = new EnumMap<>(Outcome.class);
        for (Outcome o : Outcome.values()) {
            counts.put(o, 0);
        }
        rows.forEach(r -> counts.merge(r.outcome(), 1, Integer::sum));
        return counts;
    }

    public String labelA() {
        return labelA;
    }

    public String labelB() {
        return labelB;
    }

    public TestRun runA() {
        return runA;
    }

    public TestRun runB() {
        return runB;
    }

    /** True when some test behaves differently: regression, different failure, fixed, or present in one run only. */
    public boolean hasDifferences() {
        return rows.stream().anyMatch(r -> r.outcome() != Outcome.PASSED && r.outcome() != Outcome.SAME_FAILURE
                && r.outcome() != Outcome.SKIPPED);
    }

    public boolean hasRegressions() {
        return rows.stream().anyMatch(r -> r.outcome() == Outcome.REGRESSION || r.outcome() == Outcome.DIFFERENT_FAILURE);
    }
}
