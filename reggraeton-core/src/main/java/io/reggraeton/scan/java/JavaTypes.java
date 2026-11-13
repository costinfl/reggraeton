package io.reggraeton.scan.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves type names and call targets of one compilation unit: through the symbol solver when
 * possible, otherwise through the imports (Selenium wildcard imports are checked against the
 * Selenium API on the classpath).
 */
public final class JavaTypes {

    private final CompilationUnit unit;
    private final Map<String, String> explicitImports = new HashMap<>();
    private final List<String> wildcardImports;

    public JavaTypes(CompilationUnit unit) {
        this.unit = unit;
        for (ImportDeclaration imp : unit.getImports()) {
            if (!imp.isStatic() && !imp.isAsterisk()) {
                explicitImports.put(imp.getName().getIdentifier(), imp.getNameAsString());
            }
        }
        this.wildcardImports = unit.getImports().stream()
                .filter(i -> !i.isStatic() && i.isAsterisk())
                .map(ImportDeclaration::getNameAsString)
                .toList();
    }

    /** Fully qualified name of a simple (or partially qualified) type name, if it can be determined. */
    public Optional<String> qualify(String name) {
        String first = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        String rest = name.contains(".") ? name.substring(name.indexOf('.')) : "";
        if (!first.isEmpty() && Character.isLowerCase(first.charAt(0))) {
            return Optional.of(name); // already qualified
        }
        String explicit = explicitImports.get(first);
        if (explicit != null) {
            return Optional.of(explicit + rest);
        }
        for (String pkg : wildcardImports) {
            if (classExists(pkg + "." + first)) {
                return Optional.of(pkg + "." + first + rest);
            }
        }
        return Optional.empty();
    }

    public Optional<String> qualify(ClassOrInterfaceType type) {
        return qualify(type.getNameWithScope());
    }

    /** Declaring (or receiver) type of a method call. */
    public Optional<String> declaringType(MethodCallExpr call) {
        try {
            return Optional.of(call.resolve().declaringType().getQualifiedName());
        } catch (RuntimeException | StackOverflowError e) {
            // fall through
        }
        if (call.getScope().isPresent()) {
            Expression scope = call.getScope().get();
            try {
                ResolvedType type = scope.calculateResolvedType();
                if (type.isReferenceType()) {
                    return Optional.of(type.asReferenceType().getQualifiedName());
                }
            } catch (RuntimeException | StackOverflowError e) {
                // fall through
            }
            if (scope instanceof NameExpr name) {
                Optional<String> declared = declaredType(name);
                if (declared.isPresent()) {
                    return declared;
                }
                if (Character.isUpperCase(name.getNameAsString().charAt(0))) {
                    return qualify(name.getNameAsString());
                }
            }
            if (scope instanceof MethodCallExpr inner) {
                // fluent builders of libraries that are not on the classpath (WebDriverManager.chromedriver().setup())
                Optional<String> owner = declaringType(inner);
                if (owner.isPresent() && owner.get().startsWith("io.github.bonigarcia.")) {
                    return owner;
                }
            }
            return Optional.empty();
        }
        return staticImportOwner(call.getNameAsString());
    }

    /**
     * Declared type of a variable, found syntactically (local variable, parameter or field of an
     * enclosing type) and qualified through the imports. Used when the symbol solver cannot resolve
     * the type, e.g. for classes of libraries that are not on the classpath.
     */
    public Optional<String> declaredType(NameExpr name) {
        String id = name.getNameAsString();
        for (Node p = name.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (p instanceof com.github.javaparser.ast.stmt.BlockStmt block) {
                for (var stmt : block.getStatements()) {
                    if (stmt.getBegin().isPresent() && name.getBegin().isPresent()
                            && stmt.getBegin().get().isAfter(name.getBegin().get())) {
                        break;
                    }
                    for (var v : stmt.findAll(com.github.javaparser.ast.body.VariableDeclarator.class)) {
                        if (v.getNameAsString().equals(id) && v.getType().isClassOrInterfaceType()
                                && v.findAncestor(com.github.javaparser.ast.stmt.BlockStmt.class).map(b -> b == block).orElse(false)) {
                            return qualify(v.getType().asClassOrInterfaceType());
                        }
                    }
                }
            }
            if (p instanceof com.github.javaparser.ast.nodeTypes.NodeWithParameters<?> callable) {
                for (var parameter : callable.getParameters()) {
                    if (parameter.getNameAsString().equals(id) && parameter.getType().isClassOrInterfaceType()) {
                        return qualify(parameter.getType().asClassOrInterfaceType());
                    }
                }
            }
            if (p instanceof com.github.javaparser.ast.body.TypeDeclaration<?> type) {
                for (var field : type.getFields()) {
                    for (var v : field.getVariables()) {
                        if (v.getNameAsString().equals(id) && v.getType().isClassOrInterfaceType()) {
                            return qualify(v.getType().asClassOrInterfaceType());
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** Type created by {@code new X(...)}. */
    public Optional<String> createdType(ObjectCreationExpr creation) {
        try {
            return Optional.of(creation.resolve().declaringType().getQualifiedName());
        } catch (RuntimeException | StackOverflowError e) {
            return qualify(creation.getType());
        }
    }

    private Optional<String> staticImportOwner(String member) {
        for (ImportDeclaration i : unit.getImports()) {
            if (i.isStatic() && !i.isAsterisk() && i.getNameAsString().endsWith("." + member)) {
                String name = i.getNameAsString();
                return Optional.of(name.substring(0, name.length() - member.length() - 1));
            }
        }
        for (ImportDeclaration i : unit.getImports()) {
            if (i.isStatic() && i.isAsterisk() && memberExists(i.getNameAsString(), member)) {
                return Optional.of(i.getNameAsString());
            }
        }
        return Optional.empty();
    }

    /** Whether the expression's static type is {@code java.lang.String} (best effort). */
    public static boolean isString(Expression e) {
        try {
            ResolvedType type = e.calculateResolvedType();
            return type.isReferenceType() && type.asReferenceType().getQualifiedName().equals("java.lang.String");
        } catch (RuntimeException | StackOverflowError ex) {
            return e.isStringLiteralExpr() || e.isTextBlockLiteralExpr();
        }
    }

    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, JavaTypes.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean memberExists(String className, String member) {
        try {
            Class<?> cls = Class.forName(className, false, JavaTypes.class.getClassLoader());
            for (var m : cls.getMethods()) {
                if (m.getName().equals(member)) {
                    return true;
                }
            }
            return false;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    public static String code(Node node) {
        String text = node.toString().replaceAll("\\s+", " ");
        return text.length() > 160 ? text.substring(0, 157) + "..." : text;
    }
}
