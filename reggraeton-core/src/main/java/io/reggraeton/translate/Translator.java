package io.reggraeton.translate;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import io.reggraeton.model.CallGraph;
import io.reggraeton.model.Inventory;
import io.reggraeton.scan.java.JavaSourceIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Translates the legacy glue code (step definitions, hooks, page objects, helpers) to Playwright.
 * Which files are translated: every file declaring glue used by a scenario or code reachable from
 * it, every file declaring a project type those files mention, and the parameter type classes.
 * Cucumber runner classes are left out: the generators write their own.
 */
public final class Translator {

    /** All translated files, plus the files that were selected but are runners (skipped). */
    public record Result(List<TranslatedSource> sources, List<String> skipped) {

        public List<TranslatedSource.Todo> todos() {
            return sources.stream().flatMap(s -> s.todos().stream()).toList();
        }
    }

    private Translator() {
    }

    public static Result translate(JavaSourceIndex index, Inventory inventory, Set<String> usedNodes, String supportPackage) {
        Set<CompilationUnit> selected = select(index, inventory, usedNodes);
        Set<String> runners = new HashSet<>(inventory.layout().runners());
        Set<String> methodNames = new HashSet<>();
        index.types().values().forEach(t -> t.getMethods().forEach(m -> methodNames.add(m.getNameAsString())));

        SupportNames support = new SupportNames(supportPackage);
        List<TranslatedSource> sources = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (JavaSourceIndex.Source source : index.sources()) {
            if (!selected.contains(source.unit())) {
                continue;
            }
            if (runners.contains(source.path())) {
                skipped.add(source.path());
                continue;
            }
            sources.add(new SourceTranslator(source.unit(), source.path(), index, support, methodNames).translate());
        }
        return new Result(sources, skipped);
    }

    static Set<CompilationUnit> select(JavaSourceIndex index, Inventory inventory, Set<String> usedNodes) {
        Set<String> seedTypes = new LinkedHashSet<>();
        for (CallGraph.Node node : inventory.callGraph().nodes()) {
            if (usedNodes.contains(node.id()) && node.className() != null) {
                seedTypes.add(node.className());
            }
        }
        inventory.parameterTypes().forEach(p -> seedTypes.add(p.className()));
        inventory.dataTableTypes().forEach(d -> seedTypes.add(d.className()));
        inventory.hooks().forEach(h -> seedTypes.add(h.className()));

        Map<CompilationUnit, Boolean> units = new IdentityHashMap<>();
        Deque<String> queue = new ArrayDeque<>(seedTypes);
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            String typeName = queue.poll();
            if (!seen.add(typeName)) {
                continue;
            }
            TypeDeclaration<?> type = index.types().get(typeName);
            if (type == null) {
                // nested type ids use the outer class: try progressively shorter names
                int dot = typeName.lastIndexOf('.');
                if (dot > 0) {
                    queue.add(typeName.substring(0, dot));
                }
                continue;
            }
            CompilationUnit unit = type.findCompilationUnit().orElse(null);
            if (unit == null || units.put(unit, Boolean.TRUE) != null) {
                continue;
            }
            for (ClassOrInterfaceType referenced : unit.findAll(ClassOrInterfaceType.class)) {
                for (String candidate : candidates(unit, referenced, index)) {
                    queue.add(candidate);
                }
            }
            for (MethodDeclaration m : unit.findAll(MethodDeclaration.class)) {
                m.getType().ifClassOrInterfaceType(t -> candidates(unit, t, index).forEach(queue::add));
            }
        }
        Set<CompilationUnit> result = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(units.keySet());
        return result;
    }

    /** Project types a type reference may point to (same package, imports, or unique simple name). */
    private static List<String> candidates(CompilationUnit unit, ClassOrInterfaceType type, JavaSourceIndex index) {
        String name = type.getNameAsString();
        List<String> result = new ArrayList<>();
        unit.getImports().stream()
                .filter(i -> !i.isStatic() && !i.isAsterisk() && i.getName().getIdentifier().equals(name))
                .map(i -> i.getNameAsString())
                .filter(index::isProjectType)
                .forEach(result::add);
        String pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
        if (index.isProjectType(pkg + name)) {
            result.add(pkg + name);
        }
        if (result.isEmpty()) {
            List<String> bySimpleName = index.types().keySet().stream()
                    .filter(k -> k.equals(name) || k.endsWith("." + name)).toList();
            if (bySimpleName.size() == 1) {
                result.add(bySimpleName.get(0));
            }
        }
        return result;
    }
}
