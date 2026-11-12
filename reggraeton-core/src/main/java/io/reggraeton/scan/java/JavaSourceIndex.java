package io.reggraeton.scan.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ArrayType;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.reggraeton.model.Finding;
import io.reggraeton.util.RepoFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses every Java file of the scanned repository once and wires up JavaParser's symbol solver
 * with the repository's own source roots plus the Selenium API on reggraeton's classpath (and,
 * optionally, extra jars of the scanned project), so calls can be resolved to declarations.
 */
public final class JavaSourceIndex {

    /** A parsed source file. */
    public record Source(Path file, String path, CompilationUnit unit) {
    }

    private final Path root;
    private final List<Source> sources = new ArrayList<>();
    private final Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
    private final Map<CompilationUnit, Source> sourceByUnit = new IdentityHashMap<>();
    private final List<Finding> problems = new ArrayList<>();

    private JavaSourceIndex(Path root) {
        this.root = root;
    }

    /**
     * @param root           repository root
     * @param extraClasspath jars of the scanned project (optional) to improve type resolution
     */
    public static JavaSourceIndex build(Path root, Collection<Path> extraClasspath) {
        JavaSourceIndex index = new JavaSourceIndex(root);
        index.load(extraClasspath);
        return index;
    }

    private void load(Collection<Path> extraClasspath) {
        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setStoreTokens(true);
        JavaParser parser = new JavaParser(config);

        Set<Path> sourceRoots = new LinkedHashSet<>();
        for (Path file : RepoFiles.findByExtension(root, ".java")) {
            String rel = RepoFiles.relative(root, file);
            ParseResult<CompilationUnit> result;
            try {
                result = parser.parse(Files.readString(file, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException e) {
                try {
                    result = parser.parse(Files.readString(file, java.nio.charset.StandardCharsets.ISO_8859_1));
                } catch (IOException ex) {
                    problems.add(Finding.warning("JAVA_READ_ERROR", ex.getMessage(), rel, 0));
                    continue;
                }
            }
            if (result.getResult().isEmpty() || !result.isSuccessful()) {
                String message = result.getProblems().isEmpty() ? "unparseable"
                        : result.getProblems().get(0).getVerboseMessage();
                problems.add(Finding.warning("JAVA_PARSE_ERROR", "Java file could not be parsed: " + message, rel, 0));
                if (result.getResult().isEmpty()) {
                    continue;
                }
            }
            CompilationUnit unit = result.getResult().get();
            Source source = new Source(file, rel, unit);
            sources.add(source);
            sourceByUnit.put(unit, source);
            sourceRoots.add(sourceRootOf(file, unit));
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
                type.getFullyQualifiedName().ifPresent(fqn -> types.put(fqn, type));
            }
        }

        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver(false));
        for (Path sourceRoot : sourceRoots) {
            solver.add(new JavaParserTypeSolver(sourceRoot, config));
        }
        for (Path jar : extraClasspath) {
            try {
                solver.add(new JarTypeSolver(jar));
            } catch (IOException | RuntimeException e) {
                problems.add(Finding.info("CLASSPATH_ENTRY_IGNORED", "Could not read " + jar + ": " + e.getMessage(), null, 0));
            }
        }
        JavaSymbolSolver symbolSolver = new JavaSymbolSolver(solver);
        for (Source source : sources) {
            symbolSolver.inject(source.unit());
        }
    }

    /** Directory that is the root of the package hierarchy {@code file} lives in. */
    private static Path sourceRootOf(Path file, CompilationUnit unit) {
        Path dir = file.getParent();
        Optional<String> pkg = unit.getPackageDeclaration().map(p -> p.getNameAsString());
        if (pkg.isPresent()) {
            String[] parts = pkg.get().split("\\.");
            Path candidate = dir;
            for (int i = parts.length - 1; i >= 0 && candidate != null; i--) {
                if (!candidate.getFileName().toString().equals(parts[i])) {
                    return dir; // directory layout does not follow the package
                }
                candidate = candidate.getParent();
            }
            return candidate == null ? dir : candidate;
        }
        return dir;
    }

    public Path root() {
        return root;
    }

    public List<Source> sources() {
        return sources;
    }

    public Map<String, TypeDeclaration<?>> types() {
        return types;
    }

    public boolean isProjectType(String qualifiedName) {
        return types.containsKey(qualifiedName);
    }

    public List<Finding> problems() {
        return problems;
    }

    /** Repo-relative path of the file containing {@code node}. */
    public String pathOf(Node node) {
        return node.findCompilationUnit().map(sourceByUnit::get).map(Source::path).orElse(null);
    }

    public static int lineOf(Node node) {
        return node.getBegin().map(p -> p.line).orElse(0);
    }

    public static String qualifiedName(TypeDeclaration<?> type) {
        return type.getFullyQualifiedName().orElse(type.getNameAsString());
    }

    // ----------------------------------------------------------------------------------------
    // Stable ids
    // ----------------------------------------------------------------------------------------

    /** {@code com.x.Type#name(String,int)} or {@code com.x.Type#<init>(WebDriver)}. */
    public static String callableId(CallableDeclaration<?> callable) {
        String owner = callable.findAncestor(TypeDeclaration.class)
                .map(t -> qualifiedName((TypeDeclaration<?>) t)).orElse("?");
        String name = callable instanceof ConstructorDeclaration ? "<init>" : callable.getNameAsString();
        String params = callable.getParameters().stream()
                .map(JavaSourceIndex::parameterType)
                .collect(Collectors.joining(","));
        return owner + "#" + name + "(" + params + ")";
    }

    private static String parameterType(Parameter p) {
        return simpleType(p.getType()) + (p.isVarArgs() ? "..." : "");
    }

    /** Simple, erased type name: {@code List<WebElement>} becomes {@code List}, {@code Map.Entry} becomes {@code Entry}. */
    public static String simpleType(Type type) {
        if (type instanceof ArrayType array) {
            return simpleType(array.getComponentType()) + "[]";
        }
        if (type instanceof ClassOrInterfaceType cit) {
            return cit.getNameAsString();
        }
        return type.asString();
    }

    /** Simple, erased type name from a resolved type description such as {@code java.util.List<java.lang.String>}. */
    public static String simpleTypeName(String described) {
        String erased = described.replaceAll("<.*>", "");
        boolean varargs = erased.endsWith("...");
        if (varargs) {
            erased = erased.substring(0, erased.length() - 3);
        }
        int arrays = 0;
        while (erased.endsWith("[]")) {
            erased = erased.substring(0, erased.length() - 2);
            arrays++;
        }
        String simple = erased.substring(erased.lastIndexOf('.') + 1);
        return simple + "[]".repeat(arrays) + (varargs ? "..." : "");
    }

    /**
     * Strips the package from a qualified type name, keeping enclosing types:
     * {@code org.openqa.selenium.WebDriver.Options} becomes {@code WebDriver.Options}.
     */
    public static String withoutPackage(String qualifiedName) {
        String[] parts = qualifiedName.split("\\.");
        int first = 0;
        while (first < parts.length - 1 && !parts[first].isEmpty() && Character.isLowerCase(parts[first].charAt(0))) {
            first++;
        }
        return String.join(".", java.util.Arrays.copyOfRange(parts, first, parts.length));
    }
}
