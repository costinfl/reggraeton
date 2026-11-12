package io.reggraeton.report;

import io.reggraeton.model.CallGraph;
import io.reggraeton.model.CallGraph.ApiUsage;
import io.reggraeton.model.FeatureFile;
import io.reggraeton.model.Inventory;
import io.reggraeton.model.StepMatch;
import io.reggraeton.model.TestCase;
import io.reggraeton.model.TestCaseAssessment;
import io.reggraeton.model.TestStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports the complete calling graph, from scenarios through step definitions and hooks into project
 * methods, as Graphviz DOT and as JSON.
 */
public final class CallGraphExport {

    /** JSON shape of {@code callgraph.json}. */
    public record Export(List<ScenarioNode> scenarios, List<CallGraph.Node> nodes, List<CallGraph.Edge> edges) {
    }

    /** A scenario (outline rows are folded together) and the step definitions / hooks it uses. */
    public record ScenarioNode(String id, String feature, String name, List<String> testCaseIds,
                               List<String> stepDefinitionIds, List<String> hookIds) {
    }

    private CallGraphExport() {
    }

    public static Export export(Inventory inventory, Set<String> usedNodes) {
        Map<String, TestCaseAssessment> assessments = new LinkedHashMap<>();
        inventory.assessments().forEach(a -> assessments.put(a.testCaseId(), a));

        Map<String, ScenarioNode> scenarios = new LinkedHashMap<>();
        for (TestCase tc : inventory.testCases()) {
            String id = tc.feature() + ":" + tc.scenarioLine();
            ScenarioNode existing = scenarios.get(id);
            Set<String> testCaseIds = new LinkedHashSet<>(existing == null ? List.of() : existing.testCaseIds());
            Set<String> steps = new LinkedHashSet<>(existing == null ? List.of() : existing.stepDefinitionIds());
            Set<String> hooks = new LinkedHashSet<>(existing == null ? List.of() : existing.hookIds());
            testCaseIds.add(tc.id());
            for (TestStep step : tc.steps()) {
                if (step.match() != null && StepMatch.MATCHED.equals(step.match().status())) {
                    steps.addAll(step.match().stepDefinitionIds());
                }
            }
            TestCaseAssessment a = assessments.get(tc.id());
            if (a != null) {
                hooks.addAll(a.hookIds());
            }
            scenarios.put(id, new ScenarioNode(id, tc.feature(), tc.scenarioName(), new ArrayList<>(testCaseIds),
                    new ArrayList<>(steps), new ArrayList<>(hooks)));
        }
        List<CallGraph.Node> nodes = inventory.callGraph().nodes().stream().filter(n -> usedNodes.contains(n.id())).toList();
        List<CallGraph.Edge> edges = inventory.callGraph().edges().stream()
                .filter(e -> usedNodes.contains(e.from()) && usedNodes.contains(e.to())).toList();
        return new Export(new ArrayList<>(scenarios.values()), nodes, edges);
    }

    public static String toDot(Inventory inventory, Export export) {
        StringBuilder dot = new StringBuilder();
        dot.append("// reggraeton calling graph: scenarios -> step definitions / hooks -> project code\n");
        dot.append("// render with: dot -Tsvg callgraph.dot -o callgraph.svg\n");
        dot.append("digraph reggraeton {\n  rankdir=LR;\n  node [fontname=\"Helvetica\", fontsize=10];\n");
        dot.append("  edge [color=\"#888888\", arrowsize=0.6];\n\n");

        Map<String, String> featureNames = new LinkedHashMap<>();
        for (FeatureFile f : inventory.features()) {
            featureNames.put(f.path(), f.name());
        }
        Map<String, List<ScenarioNode>> byFeature = new LinkedHashMap<>();
        export.scenarios().forEach(s -> byFeature.computeIfAbsent(s.feature(), k -> new ArrayList<>()).add(s));

        int cluster = 0;
        for (Map.Entry<String, List<ScenarioNode>> feature : byFeature.entrySet()) {
            dot.append("  subgraph cluster_").append(cluster++).append(" {\n    label=").append(q("Feature: "
                    + featureNames.getOrDefault(feature.getKey(), feature.getKey()))).append(";\n    style=rounded; color=\"#bbbbbb\";\n");
            for (ScenarioNode s : feature.getValue()) {
                String label = escape(s.name()) + (s.testCaseIds().size() > 1 ? "\\n(" + s.testCaseIds().size() + " example rows)" : "");
                dot.append("    ").append(q("scenario:" + s.id())).append(" [shape=box, style=\"rounded,filled\", fillcolor=\"#eef3fb\", label=")
                        .append(qRaw(label)).append("];\n");
            }
            dot.append("  }\n");
        }
        dot.append("\n");

        for (CallGraph.Node n : export.nodes()) {
            boolean manual = n.apiUsages().stream().anyMatch(u -> ApiUsage.MANUAL.equals(u.support()));
            String shape = switch (n.kind()) {
                case "step" -> "shape=ellipse, style=filled, fillcolor=\"#e3f4ea\"";
                case "hook" -> "shape=ellipse, style=filled, fillcolor=\"#efe6f8\"";
                case "class" -> "shape=note, style=dashed";
                default -> "shape=box";
            };
            String simpleClass = n.className() == null ? "" : n.className().substring(n.className().lastIndexOf('.') + 1);
            String label = escape("class".equals(n.kind()) ? simpleClass : simpleClass + "." + n.name())
                    + ("class".equals(n.kind()) ? "\\n(fields)" : "");
            long seleniumCalls = n.apiUsages().stream().filter(u -> ApiUsage.SELENIUM.equals(u.category())).count();
            if (seleniumCalls > 0) {
                label += "\\n" + seleniumCalls + " selenium call" + (seleniumCalls > 1 ? "s" : "");
            }
            dot.append("  ").append(q(n.id())).append(" [").append(shape)
                    .append(manual ? ", color=\"#c0392b\", penwidth=2" : "")
                    .append(", label=").append(qRaw(label)).append("];\n");
        }
        dot.append("\n");
        for (ScenarioNode s : export.scenarios()) {
            for (String step : s.stepDefinitionIds()) {
                dot.append("  ").append(q("scenario:" + s.id())).append(" -> ").append(q(step)).append(";\n");
            }
        }
        for (CallGraph.Edge e : export.edges()) {
            dot.append("  ").append(q(e.from())).append(" -> ").append(q(e.to()))
                    .append(e.resolved() ? "" : " [style=dashed]").append(";\n");
        }
        dot.append("}\n");
        return dot.toString();
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Quotes a label whose parts were escaped with {@link #escape} and joined with {@code \n}. */
    private static String qRaw(String s) {
        return "\"" + s + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
