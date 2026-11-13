package io.reggraeton.scan.java;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Project classes that implement (or extend) Selenium's driver types, e.g.
 * {@code class SmartDriver implements WebDriver, JavascriptExecutor}, typically decorating a real
 * driver. After translation they no longer implement anything Selenium: they get a {@code page()}
 * method, keep the methods whose signatures translate (findElement, get, executeScript ...), and
 * lose the ones that return Selenium-only types (switchTo, manage, navigate, getScreenshotAs ...),
 * whose callers then go through {@code page()}.
 */
public final class DriverWrappers {

    /** Selenium types that make a project class a driver wrapper. */
    private static final Set<String> DRIVER_TYPES = Set.of(
            "org.openqa.selenium.WebDriver", "org.openqa.selenium.JavascriptExecutor", "org.openqa.selenium.TakesScreenshot",
            "org.openqa.selenium.SearchContext", "org.openqa.selenium.HasCapabilities", "org.openqa.selenium.WrapsDriver",
            "org.openqa.selenium.interactions.Interactive", "org.openqa.selenium.remote.RemoteWebDriver",
            "org.openqa.selenium.chrome.ChromeDriver", "org.openqa.selenium.edge.EdgeDriver",
            "org.openqa.selenium.firefox.FirefoxDriver", "org.openqa.selenium.support.events.EventFiringWebDriver");

    /** Selenium types the translator maps to a Playwright / support type (anything else becomes Object). */
    private static final Set<String> TRANSLATABLE = Set.of(
            "org.openqa.selenium.WebDriver", "org.openqa.selenium.JavascriptExecutor", "org.openqa.selenium.TakesScreenshot",
            "org.openqa.selenium.SearchContext", "org.openqa.selenium.WebElement", "org.openqa.selenium.By",
            "org.openqa.selenium.Keys", "org.openqa.selenium.Alert", "org.openqa.selenium.Cookie",
            "org.openqa.selenium.support.ui.Select", "org.openqa.selenium.support.ui.WebDriverWait",
            "org.openqa.selenium.support.ui.FluentWait", "org.openqa.selenium.support.ui.Wait");

    /** A wrapper class and the methods whose callers must go through {@code page()}. */
    public record Wrapper(String className, Set<String> redirected, List<String> seleniumSupertypes, boolean extendsSeleniumClass) {

        public boolean redirects(String method, int arity) {
            return redirected.contains(method + "/" + arity);
        }
    }

    private final Map<String, Wrapper> wrappers = new LinkedHashMap<>();

    private DriverWrappers() {
    }

    public static DriverWrappers find(JavaSourceIndex index) {
        DriverWrappers result = new DriverWrappers();
        for (Map.Entry<String, TypeDeclaration<?>> entry : index.types().entrySet()) {
            if (!(entry.getValue() instanceof ClassOrInterfaceDeclaration cls) || cls.isInterface()) {
                continue;
            }
            JavaTypes types = cls.findCompilationUnit().map(JavaTypes::new).orElse(null);
            if (types == null) {
                continue;
            }
            List<String> supertypes = new java.util.ArrayList<>();
            boolean extendsSelenium = false;
            for (ClassOrInterfaceType t : cls.getImplementedTypes()) {
                types.qualify(t).filter(DRIVER_TYPES::contains).ifPresent(supertypes::add);
            }
            for (ClassOrInterfaceType t : cls.getExtendedTypes()) {
                Optional<String> q = types.qualify(t);
                if (q.isPresent() && DRIVER_TYPES.contains(q.get())) {
                    supertypes.add(q.get());
                    extendsSelenium = true;
                }
            }
            if (supertypes.isEmpty()) {
                continue;
            }
            Set<String> redirected = new LinkedHashSet<>();
            for (MethodDeclaration m : cls.getMethods()) {
                if (!m.isStatic() && usesUntranslatableSeleniumType(m, types, supertypes)) {
                    redirected.add(m.getNameAsString() + "/" + m.getParameters().size());
                }
            }
            result.wrappers.put(entry.getKey(), new Wrapper(entry.getKey(), redirected, supertypes, extendsSelenium));
        }
        return result;
    }

    private static boolean usesUntranslatableSeleniumType(MethodDeclaration m, JavaTypes types, List<String> supertypes) {
        if (untranslatable(m.getType(), types, supertypes)) {
            return true;
        }
        for (var p : m.getParameters()) {
            if (untranslatable(p.getType(), types, supertypes)) {
                return true;
            }
        }
        // generic methods such as <X> X getScreenshotAs(OutputType<X>) are covered by the parameter check
        return false;
    }

    private static boolean untranslatable(Type type, JavaTypes types, List<String> supertypes) {
        for (ClassOrInterfaceType t : type.findAll(ClassOrInterfaceType.class)) {
            Optional<String> q = types.qualify(t);
            if (q.isEmpty()) {
                q = inheritedMemberType(t.getNameWithScope(), supertypes);
            }
            if (q.isPresent() && q.get().startsWith("org.openqa.selenium.") && !TRANSLATABLE.contains(q.get())) {
                return true;
            }
        }
        return false;
    }

    /** {@code TargetLocator} written inside a class implementing WebDriver means {@code WebDriver.TargetLocator}. */
    static Optional<String> inheritedMemberType(String name, List<String> supertypes) {
        for (String supertype : supertypes) {
            try {
                Class<?> cls = Class.forName(supertype);
                for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
                    for (Class<?> nested : allMemberTypes(c)) {
                        if (nested.getSimpleName().equals(name)) {
                            return Optional.of(nested.getCanonicalName());
                        }
                    }
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // not on the classpath
            }
        }
        return Optional.empty();
    }

    private static List<Class<?>> allMemberTypes(Class<?> c) {
        List<Class<?>> result = new java.util.ArrayList<>(List.of(c.getDeclaredClasses()));
        for (Class<?> i : c.getInterfaces()) {
            result.addAll(allMemberTypes(i));
        }
        return result;
    }

    public boolean isEmpty() {
        return wrappers.isEmpty();
    }

    public Optional<Wrapper> get(String className) {
        return Optional.ofNullable(wrappers.get(className));
    }

    public Map<String, Wrapper> all() {
        return wrappers;
    }

    /** The Selenium interface ({@code WebDriver}, {@code JavascriptExecutor} ...) declaring {@code method/arity}. */
    public static Optional<String> seleniumInterfaceOf(String method, int arity) {
        for (String name : List.of("org.openqa.selenium.WebDriver", "org.openqa.selenium.JavascriptExecutor",
                "org.openqa.selenium.TakesScreenshot", "org.openqa.selenium.HasCapabilities")) {
            try {
                for (var declared : Class.forName(name).getMethods()) {
                    if (declared.getName().equals(method) && (declared.getParameterCount() == arity || declared.isVarArgs())) {
                        return Optional.of(name);
                    }
                }
            } catch (ClassNotFoundException | LinkageError e) {
                // not on the classpath
            }
        }
        return Optional.empty();
    }
}
