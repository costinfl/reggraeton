package io.reggraeton.scan;

import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;
import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.model.Finding;
import io.reggraeton.model.Hook;
import io.reggraeton.model.ProjectLayout;
import io.reggraeton.model.StepDefinition;
import io.reggraeton.model.StepMatch;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.model.TestStep;
import io.reggraeton.scan.java.CallGraphBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns matches and the call graph into per-test-case readiness and a list of findings.
 */
public final class Assessor {

    /** Reachability summary of one entry point (step definition or hook). */
    record Reach(Set<String> nodes, List<ApiUsage> manualUsages) {
    }

    private final CallGraph graph;
    private final Map<String, List<String>> adjacency = new HashMap<>();
    private final Map<String, CallGraph.Node> nodesById = new LinkedHashMap<>();
    private final Map<String, Reach> reachCache = new HashMap<>();

    public Assessor(CallGraph graph) {
        this.graph = graph;
        for (CallGraph.Node node : graph.nodes()) {
            nodesById.put(node.id(), node);
        }
        for (CallGraph.Edge edge : graph.edges()) {
            adjacency.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge.to());
        }
    }

    /** Call-graph nodes reachable from the step definitions and hooks the test cases actually use. */
    public static Set<String> usedNodes(io.reggraeton.model.Inventory inventory) {
        Assessor assessor = new Assessor(inventory.callGraph());
        Set<String> used = new LinkedHashSet<>();
        for (TestCaseAssessment a : inventory.assessments()) {
            a.stepDefinitionIds().forEach(id -> used.addAll(assessor.reachable(id)));
            a.hookIds().forEach(id -> used.addAll(assessor.reachable(id)));
        }
        inventory.hooks().stream().filter(h -> h.phase().endsWith("All"))
                .forEach(h -> used.addAll(assessor.reachable(h.id())));
        return used;
    }

    /** Nodes reachable from {@code entry} (inclusive). */
    public Set<String> reachable(String entry) {
        return reach(entry).nodes();
    }

    private Reach reach(String entry) {
        return reachCache.computeIfAbsent(entry, start -> {
            Set<String> seen = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                String id = queue.poll();
                if (!seen.add(id)) {
                    continue;
                }
                adjacency.getOrDefault(id, List.of()).forEach(queue::add);
            }
            List<ApiUsage> manual = seen.stream()
                    .map(nodesById::get).filter(n -> n != null)
                    .flatMap(n -> n.apiUsages().stream())
                    .filter(u -> ApiUsage.MANUAL.equals(u.support()))
                    .collect(Collectors.toList());
            return new Reach(seen, manual);
        });
    }

    public TestCaseAssessment assess(TestCase testCase, List<Hook> hooks) {
        List<String> issues = new ArrayList<>();
        Set<String> stepIds = new LinkedHashSet<>();
        boolean blocked = false;
        for (TestStep step : testCase.steps()) {
            StepMatch match = step.match();
            if (match == null || StepMatch.UNDEFINED.equals(match.status())) {
                blocked = true;
                issues.add("Undefined step (line " + step.line() + "): " + step.text());
            } else if (StepMatch.AMBIGUOUS.equals(match.status())) {
                blocked = true;
                issues.add("Ambiguous step (line " + step.line() + "): " + step.text() + " matches " + match.stepDefinitionIds());
            } else {
                stepIds.addAll(match.stepDefinitionIds());
            }
        }
        List<String> hookIds = hooks.stream()
                .filter(h -> !h.phase().endsWith("All")) // BeforeAll/AfterAll run once per run, not per test
                .filter(h -> appliesTo(h, testCase.tags()))
                .map(Hook::id)
                .collect(Collectors.toList());

        Set<String> reached = new LinkedHashSet<>();
        Set<ApiUsage> manual = new LinkedHashSet<>();
        for (String entry : concat(stepIds, hookIds)) {
            Reach r = reach(entry);
            reached.addAll(r.nodes());
            manual.addAll(r.manualUsages());
        }
        for (ApiUsage usage : manual) {
            issues.add("Manual conversion: " + usage.api() + " — " + usage.note());
        }
        long methods = reached.stream().map(nodesById::get)
                .filter(n -> n != null && !CallGraphBuilder.KIND_CLASS.equals(n.kind()))
                .count();
        String status = blocked ? TestCaseAssessment.BLOCKED
                : manual.isEmpty() ? TestCaseAssessment.READY : TestCaseAssessment.PARTIAL;
        return new TestCaseAssessment(testCase.id(), status, new ArrayList<>(stepIds), hookIds,
                (int) methods, manual.size(), issues);
    }

    private static List<String> concat(Set<String> a, List<String> b) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    public static boolean appliesTo(Hook hook, List<String> tags) {
        if (hook.tagExpression() == null || hook.tagExpression().isBlank()) {
            return true;
        }
        try {
            Expression expression = TagExpressionParser.parse(hook.tagExpression());
            return expression.evaluate(tags);
        } catch (RuntimeException e) {
            return true; // conservative: an unparseable expression is reported separately
        }
    }

    // ----------------------------------------------------------------------------------------
    // Findings
    // ----------------------------------------------------------------------------------------

    public static List<Finding> findings(ProjectLayout layout, List<TestCase> testCases,
                                         List<StepDefinition> definitions, List<Hook> hooks,
                                         CallGraph graph, Set<String> usedNodes) {
        List<Finding> findings = new ArrayList<>();

        Set<String> used = new LinkedHashSet<>();
        Set<String> reportedUndefined = new LinkedHashSet<>();
        for (TestCase tc : testCases) {
            for (TestStep step : tc.steps()) {
                StepMatch m = step.match();
                if (m == null) {
                    continue;
                }
                if (StepMatch.UNDEFINED.equals(m.status())) {
                    if (reportedUndefined.add(tc.feature() + ":" + step.line())) {
                        findings.add(Finding.error("UNDEFINED_STEP",
                                "No step definition matches \"" + step.text() + "\"", tc.feature(), step.line()));
                    }
                } else if (StepMatch.AMBIGUOUS.equals(m.status())) {
                    if (reportedUndefined.add(tc.feature() + ":" + step.line())) {
                        findings.add(Finding.error("AMBIGUOUS_STEP",
                                "\"" + step.text() + "\" matches " + m.stepDefinitionIds().size()
                                        + " step definitions: " + String.join(", ", m.stepDefinitionIds()),
                                tc.feature(), step.line()));
                    }
                }
                used.addAll(m.stepDefinitionIds());
            }
        }
        for (StepDefinition def : definitions) {
            if (!used.contains(def.id())) {
                findings.add(Finding.info("UNUSED_STEP_DEFINITION",
                        "Step definition \"" + def.expression() + "\" is not used by any scenario", def.file(), def.line()));
            }
        }
        for (Hook hook : hooks) {
            if (hook.tagExpression() != null && !hook.tagExpression().isBlank()) {
                try {
                    TagExpressionParser.parse(hook.tagExpression());
                } catch (RuntimeException e) {
                    findings.add(Finding.warning("INVALID_TAG_EXPRESSION",
                            "Hook tag expression \"" + hook.tagExpression() + "\" is invalid: " + e.getMessage(),
                            hook.file(), hook.line()));
                }
            }
        }
        for (CallGraph.Node node : graph.nodes()) {
            if (!usedNodes.contains(node.id())) {
                continue;
            }
            for (ApiUsage usage : node.apiUsages()) {
                if (ApiUsage.MANUAL.equals(usage.support())) {
                    findings.add(Finding.warning("MANUAL_CONVERSION",
                            usage.api() + ": " + usage.note() + " [" + usage.code() + "]", node.file(), usage.line()));
                }
            }
        }
        if ("spring".equals(layout.dependencyInjection()) || "guice".equals(layout.dependencyInjection())) {
            findings.add(Finding.warning("DEPENDENCY_INJECTION",
                    "Step classes are wired with " + layout.dependencyInjection()
                            + "; generated code uses constructor injection and may need manual wiring", null, 0));
        }
        return findings;
    }
}
